package dev.borovas.animadroid.feature.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer + MediaSession. The default media notification (foreground, mediaPlayback) is
 * posted automatically by [MediaSessionService]. The UI talks to it through a MediaController.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(HeaderAwareMediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, player).apply { launch?.let(::setSessionActivity) }.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Builds a media source per item, using the request headers (and optional external audio) carried in
 * [MediaItem.mediaMetadata] extras by [MediaItemFactory].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HeaderAwareMediaSourceFactory : MediaSource.Factory {
    private var policy: LoadErrorHandlingPolicy? = null

    override fun setLoadErrorHandlingPolicy(p: LoadErrorHandlingPolicy): MediaSource.Factory = apply { policy = p }

    override fun setDrmSessionManagerProvider(
        p: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras: Bundle? = mediaItem.mediaMetadata.extras
        val headers = buildMap {
            extras?.getBundle(MediaItemFactory.EXTRA_HEADERS)?.let { b ->
                b.keySet().forEach { k -> b.getString(k)?.let { put(k, it) } }
            }
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
        val factory = DefaultMediaSourceFactory(http).also { f -> policy?.let { f.setLoadErrorHandlingPolicy(it) } }
        val main = factory.createMediaSource(mediaItem)
        val audioUrl = extras?.getString(MediaItemFactory.EXTRA_AUDIO_URL) ?: return main
        val audio = factory.createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(main, audio)
    }
}
