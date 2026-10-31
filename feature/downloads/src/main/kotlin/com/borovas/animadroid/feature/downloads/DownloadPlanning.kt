package com.borovas.animadroid.feature.downloads

import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.QualityPreference
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure helpers (no Android types) for turning a [StreamSet] into download parts and for mapping Media3
 * download states back onto one episode-level [DownloadState]. Unit tested.
 *
 * Design: one episode = one *main* Media3 download (the chosen HLS variant playlist, all its segments and
 * keys) plus optional *sidecar* downloads (external audio playlist, each subtitle file). Every part is a
 * separate Media3 download whose id is `<episodeKey>` / `<episodeKey>#audio` / `<episodeKey>#subN`; all of
 * them write into the one shared SimpleCache under their plain URL cache keys, so the player (which uses
 * the same URLs from the stored snapshot) finds every byte offline without any special MediaSource.
 */
object DownloadIds {
    private const val SEP = '\u001f'
    private const val PART = '#'

    fun episodeKey(providerId: String, animeId: String, episodeNumber: String) = "$providerId$SEP$animeId$SEP$episodeNumber"

    /** Id of the main part == the episode key. */
    fun mainId(key: String) = key

    fun audioId(key: String) = "$key${PART}audio"
    fun subtitleId(key: String, index: Int) = "$key${PART}sub$index"

    /** Episode key of any part id. */
    fun keyOf(partId: String): String = partId.substringBefore(PART)
    fun isMain(partId: String): Boolean = !partId.contains(PART)

    data class Parsed(val providerId: String, val animeId: String, val episodeNumber: String)

    fun parse(key: String): Parsed? {
        val p = key.split(SEP)
        return if (p.size == 3) Parsed(p[0], p[1], p[2]) else null
    }
}

/** Serialized into `DownloadRequest.data`: how the downloader must talk to the CDN. */
@Serializable
data class DownloadPayload(val headers: Map<String, String> = emptyMap(), val label: String = "") {
    fun encode(): ByteArray = Json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        fun decode(bytes: ByteArray?): DownloadPayload =
            if (bytes == null || bytes.isEmpty()) DownloadPayload()
            else runCatching { Json.decodeFromString(serializer(), bytes.decodeToString()) }.getOrDefault(DownloadPayload())
    }
}

/** One Media3 download to submit. */
data class PartRequest(val id: String, val url: String, val isHls: Boolean)

object DownloadPlanner {
    data class Plan(val snapshot: StreamSet, val quality: Int?)

    private fun targetHeight(pref: QualityPreference): Int? = when (pref) {
        QualityPreference.P1080 -> 1080
        QualityPreference.P720 -> 720
        QualityPreference.P480 -> 480
        QualityPreference.P360 -> 360
        else -> null
    }

    /** Same rule as the player's QualitySelector: exact match, else nearest lower, else best. */
    fun selectVariant(variants: List<StreamVariant>, pref: QualityPreference): StreamVariant? {
        if (variants.isEmpty()) return null
        val known = variants.filter { it.quality != null }
        val height = targetHeight(pref)
        val best = known.maxByOrNull { it.quality!! } ?: variants.first()
        return when {
            height != null -> known.firstOrNull { it.quality == height }
                ?: known.filter { it.quality!! < height }.maxByOrNull { it.quality!! }
                ?: best
            pref == QualityPreference.WORST -> known.minByOrNull { it.quality!! } ?: variants.last()
            else -> best
        }
    }

    /**
     * Offline storage always keeps a single rendition (no ABR): the variant matching [pref]. When the provider
     * offers no variant list we fall back to the master playlist (every rendition is then downloaded).
     * Returns null when nothing is downloadable.
     */
    fun plan(set: StreamSet, pref: QualityPreference): Plan? {
        val variant = selectVariant(set.variants, pref)
        return when {
            variant != null -> Plan(set.copy(masterUrl = null, variants = listOf(variant)), variant.quality)
            set.masterUrl != null -> Plan(set.copy(variants = emptyList()), null)
            else -> null
        }
    }

    fun isHls(url: String) = url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    /** Main part first, then sidecars. [snapshot] is the (already planned) stream set. */
    fun parts(key: String, snapshot: StreamSet): List<PartRequest> = buildList {
        val main = snapshot.variants.firstOrNull()?.url ?: snapshot.masterUrl ?: return emptyList()
        add(PartRequest(DownloadIds.mainId(key), main, isHls(main)))
        snapshot.externalAudioUrl?.let { add(PartRequest(DownloadIds.audioId(key), it, isHls(it))) }
        snapshot.subtitles.distinctBy { it.url }.forEachIndexed { i, s -> add(PartRequest(DownloadIds.subtitleId(key, i), s.url, false)) }
    }
}

/** Snapshot of one Media3 download part, decoupled from media3 types. */
data class PartState(val id: String, val state: Int, val bytes: Long, val percent: Float) {
    companion object {
        // Mirror androidx.media3.exoplayer.offline.Download.STATE_* (asserted in tests).
        const val QUEUED = 0
        const val STOPPED = 1
        const val DOWNLOADING = 2
        const val COMPLETED = 3
        const val FAILED = 4
        const val REMOVING = 5
        const val RESTARTING = 7
    }
}

data class Aggregate(val state: DownloadState, val bytes: Long, val percent: Float)

object DownloadAggregator {
    /** Combines the parts of one episode. Returns null when there are no parts (nothing known). */
    fun aggregate(parts: List<PartState>): Aggregate? {
        val live = parts.filter { it.state != PartState.REMOVING }
        if (live.isEmpty()) return null
        val bytes = live.sumOf { it.bytes }
        val main = live.firstOrNull { DownloadIds.isMain(it.id) }
        val state = when {
            live.any { it.state == PartState.FAILED } -> DownloadState.FAILED
            main != null && live.all { it.state == PartState.COMPLETED } -> DownloadState.COMPLETED
            live.any { it.state == PartState.STOPPED } -> DownloadState.PAUSED
            live.any { it.state == PartState.DOWNLOADING } -> DownloadState.DOWNLOADING
            else -> DownloadState.QUEUED
        }
        val percent = when {
            state == DownloadState.COMPLETED -> 1f
            main == null || main.percent < 0f -> 0f
            main.state == PartState.COMPLETED -> 0.99f
            else -> (main.percent / 100f).coerceIn(0f, 0.99f)
        }
        return Aggregate(state, bytes, percent)
    }
}
