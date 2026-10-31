package com.borovas.animadroid.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Lifecycle of an offline download of one episode (aggregated over main stream + sidecars). */
@Serializable
enum class DownloadState {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED;

    val isActive: Boolean get() = this == QUEUED || this == DOWNLOADING
}

/**
 * One downloaded (or downloading) episode.
 * [streamSet] is a snapshot of what was actually downloaded: a single variant (or the master when the
 * provider offered no variants), the headers, external audio and subtitles. The player replays exactly
 * this through the shared download cache, so it works without any provider call.
 */
@Serializable
data class DownloadItem(
    val providerId: String,
    val animeId: String,
    val episodeId: String,
    val episodeNumber: String,
    /** Anime title. */
    val title: String,
    val coverUrl: String? = null,
    val mode: TranslationMode = TranslationMode.SUB,
    /** Height of the downloaded variant, when known. */
    val quality: Int? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val bytes: Long = 0,
    /** 0f..1f. */
    val percent: Float = 0f,
    val createdAt: Long = 0,
    val streamSet: StreamSet? = null,
    /** Human-readable reason for [DownloadState.FAILED], or a note such as "Paused by system" for [DownloadState.PAUSED]. */
    val failureReason: String? = null,
) {
    val key: String get() = "$providerId/$animeId/$episodeNumber"
    val isPlayable: Boolean get() = state == DownloadState.COMPLETED && streamSet != null
}

/** All downloads of one anime, for the grouped downloads screen. */
data class DownloadGroup(
    val providerId: String,
    val animeId: String,
    val title: String,
    val coverUrl: String?,
    val items: List<DownloadItem>,
) {
    val totalBytes: Long get() = items.sumOf { it.bytes }
}

/** Implemented in :core:database. */
interface DownloadRepository {
    fun downloads(): Flow<List<DownloadItem>>

    /** Grouped by anime, newest download first; episodes sorted by number. */
    fun groupedByAnime(): Flow<List<DownloadGroup>>

    /** Episode number -> download, for one anime. */
    fun forAnime(providerId: String, animeId: String): Flow<Map<String, DownloadItem>>
    fun observe(providerId: String, animeId: String, episodeNumber: String): Flow<DownloadItem?>
    suspend fun get(providerId: String, animeId: String, episodeNumber: String): DownloadItem?
    suspend fun getAll(): List<DownloadItem>
    suspend fun upsert(item: DownloadItem)

    /** Updates state/bytes/percent/failureReason only; no-op when the row does not exist. */
    suspend fun updateProgress(
        providerId: String, animeId: String, episodeNumber: String,
        state: DownloadState, bytes: Long, percent: Float,
        failureReason: String? = null,
    )

    suspend fun delete(providerId: String, animeId: String, episodeNumber: String)
}

/** Implemented in :feature:downloads (queues work on the Media3 DownloadManager). */
interface DownloadController {
    /** Fetches streams (quality = user's preference), then queues the download. Never throws. */
    suspend fun enqueue(anime: Anime, episode: Episode, mode: TranslationMode)

    /** Queues several episodes one after another (keeps provider load low); survives the caller leaving. */
    suspend fun enqueueAll(anime: Anime, episodes: List<Episode>, mode: TranslationMode)
    fun pause(providerId: String, animeId: String, episodeNumber: String)
    fun resume(providerId: String, animeId: String, episodeNumber: String)

    /** Cancels an active download or deletes a finished one, including cached data. */
    fun remove(providerId: String, animeId: String, episodeNumber: String)
}
