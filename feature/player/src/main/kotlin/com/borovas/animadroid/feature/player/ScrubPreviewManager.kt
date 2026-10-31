package com.borovas.animadroid.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.ImageBitmap
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.feature.downloads.CacheDataSources
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** Builds one [ScrubPreviewManager] per player screen. */
class ScrubPreviewManagerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val cacheSources: CacheDataSources,
) {
    fun create(isMainPlayerBuffering: () -> Boolean) = ScrubPreviewManager(context, client, cacheSources, isMainPlayerBuffering)
}

/**
 * Owns the scrub-preview source of the current episode: the provider sprite sheet when there is one (always allowed,
 * ~2 MB once), otherwise the decode fallback ([PlayerFramePreviewSource]) which is only used when the user enabled
 * previews, the main player is not buffering, and the network is unmetered (or mobile data was opted in, or the episode
 * is a finished offline download which needs no network).
 */
class ScrubPreviewManager internal constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val cacheSources: CacheDataSources,
    private val isMainPlayerBuffering: () -> Boolean,
) {
    private val _source = MutableStateFlow<ScrubPreviewSource?>(null)
    val source: StateFlow<ScrubPreviewSource?> = _source.asStateFlow()

    fun configure(set: StreamSet, offline: Boolean, settings: AppSettings) {
        clear()
        if (!settings.scrubPreviewEnabled) return
        val sprite = set.spriteVttUrl?.let { SpriteSheetPreviewSource(client, it, set.headers) }
        val rendition = lowestRendition(set)
        val fallback = rendition?.let { (url, height) ->
            PlayerFramePreviewSource(context, cacheSources, url, set.headers, height) {
                !isMainPlayerBuffering() && (offline || settings.scrubPreviewMobileData || isUnmetered())
            }
        }
        if (sprite == null && fallback == null) return
        _source.value = CompositeScrubPreviewSource(sprite, fallback)
    }

    fun clear() {
        _source.value?.close()
        _source.value = null
    }

    fun close() = clear()

    private fun isUnmetered(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (e: SecurityException) {
        false
    }

    internal companion object {
        /** Cheapest rendition to decode previews from: the lowest known quality (<= 360p when offered), else the master. */
        fun lowestRendition(set: StreamSet): Pair<String, Int>? {
            val known = set.variants.filter { it.quality != null }
            val v = known.minByOrNull { it.quality!! } ?: set.variants.lastOrNull()
            return when {
                v != null -> v.url to (v.quality ?: 360)
                set.masterUrl != null -> set.masterUrl!! to 360
                else -> null
            }
        }
    }
}

/** Sprite first; when it is absent, failed, or has no tile for the position, the decode fallback. */
internal class CompositeScrubPreviewSource(
    private val sprite: SpriteSheetPreviewSource?,
    private val fallback: PlayerFramePreviewSource?,
) : ScrubPreviewSource {
    override suspend fun frameAt(positionMs: Long): ImageBitmap? {
        if (sprite != null && !sprite.failed) sprite.frameAt(positionMs)?.let { return it }
        return fallback?.frameAt(positionMs)
    }

    override fun onScrubEnd() { fallback?.onScrubEnd() }

    override fun close() {
        sprite?.close()
        fallback?.close()
    }
}
