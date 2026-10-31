package com.borovas.animadroid.feature.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.borovas.animadroid.core.model.QualityPreference
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.SubtitleTrack

/** Pure quality-selection logic (ani-cli style: exact match, else nearest lower, else best). */
object QualitySelector {
    fun targetHeight(pref: QualityPreference): Int? = when (pref) {
        QualityPreference.P1080 -> 1080
        QualityPreference.P720 -> 720
        QualityPreference.P480 -> 480
        QualityPreference.P360 -> 360
        else -> null
    }

    /** Whether the master (ABR) playlist should be played instead of a single variant. */
    fun useMaster(streams: StreamSet, pref: QualityPreference, explicitHeight: Int? = null): Boolean =
        streams.masterUrl != null &&
            ((pref == QualityPreference.BEST && explicitHeight == null) || streams.variants.isEmpty())

    fun selectVariant(variants: List<StreamVariant>, pref: QualityPreference, explicitHeight: Int? = null): StreamVariant? {
        if (variants.isEmpty()) return null
        val known = variants.filter { it.quality != null }
        val height = explicitHeight ?: targetHeight(pref)
        return when {
            height != null -> known.firstOrNull { it.quality == height }
                ?: known.filter { it.quality!! < height }.maxByOrNull { it.quality!! }
                ?: bestOf(variants)
            pref == QualityPreference.WORST -> known.minByOrNull { it.quality!! } ?: variants.last()
            else -> bestOf(variants)
        }
    }

    private fun bestOf(variants: List<StreamVariant>): StreamVariant =
        variants.filter { it.quality != null }.maxByOrNull { it.quality!! } ?: variants.first()

    /** Resolves the URL to play, or null when there is nothing playable. */
    fun resolveUrl(streams: StreamSet, pref: QualityPreference, explicitHeight: Int? = null): String? =
        if (useMaster(streams, pref, explicitHeight)) streams.masterUrl
        else selectVariant(streams.variants, pref, explicitHeight)?.url ?: streams.masterUrl

    fun subtitleMimeType(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_VTT
        }
    }
}

/** Builds [MediaItem]s. Headers and external audio travel in [MediaMetadata.extras] to the service. */
object MediaItemFactory {
    const val EXTRA_HEADERS = "animadroid.headers"
    const val EXTRA_AUDIO_URL = "animadroid.audio_url"

    fun create(
        streams: StreamSet,
        pref: QualityPreference,
        title: String,
        explicitHeight: Int? = null,
        mediaId: String = title,
    ): MediaItem? {
        val url = QualitySelector.resolveUrl(streams, pref, explicitHeight) ?: return null
        val extras = Bundle().apply {
            putBundle(EXTRA_HEADERS, Bundle().apply { streams.headers.forEach { (k, v) -> putString(k, v) } })
            streams.externalAudioUrl?.let { putString(EXTRA_AUDIO_URL, it) }
        }
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setExtras(extras).build())
            .setSubtitleConfigurations(streams.subtitles.map(::subtitleConfig))
        if (url.substringBefore('?').endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun subtitleConfig(t: SubtitleTrack): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(t.url))
            .setMimeType(QualitySelector.subtitleMimeType(t.url))
            .setLanguage(t.language)
            .setLabel(t.label ?: t.language)
            .setSelectionFlags(if (t.isDefault) androidx.media3.common.C.SELECTION_FLAG_DEFAULT else 0)
            .build()
}
