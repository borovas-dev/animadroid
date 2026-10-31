package com.borovas.animadroid.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.borovas.animadroid.feature.downloads.CacheDataSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fallback scrub preview for streams without a thumbnail sprite: a SECOND, deliberately tiny ExoPlayer decodes single
 * frames of the lowest rendition (<= 360p) and hands them over through an [ImageReader] surface.
 *
 * Why ImageReader (YUV_420_888) and not TextureView/SurfaceView+PixelCopy: it needs no view hierarchy / window (so it
 * works while the controls are fading, in PiP and across rotation), has no visible side effects, and YUV_420_888 is the
 * one format MediaCodec output surfaces are guaranteed to feed an ImageReader with. The ~230k-pixel YUV->ARGB
 * conversion is a few ms off the main thread.
 *
 * Cost control: a request is debounced ([DEBOUNCE_MS]) and snapped to a [GRID_MS] grid (HLS can only start at segment
 * boundaries anyway, [SeekParameters.CLOSEST_SYNC]), stale requests are cancelled by the caller, recent frames sit in a
 * byte-capped LRU, the player buffers ~1.5-3 s only, and it is released [IDLE_RELEASE_MS] after the scrub ends.
 * [allowed] is polled before touching the network/decoder (settings, metered network, main player buffering).
 * For finished downloads [cacheSources] serves every byte from the offline cache, no network at all.
 */
@OptIn(UnstableApi::class)
internal class PlayerFramePreviewSource(
    private val context: Context,
    private val cacheSources: CacheDataSources,
    private val url: String,
    private val headers: Map<String, String>,
    /** Height of the chosen rendition; only sizes the ImageReader (real frame size is read from the Image). */
    private val renditionHeight: Int,
    private val allowed: () -> Boolean,
) : ScrubPreviewSource {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestLock = Mutex()
    private val cache = LruMap<Long, ImageBitmap>(CACHE_BYTES, { (it.width.toLong() * it.height * 4) })
    private var player: ExoPlayer? = null
    private var reader: ImageReader? = null
    private var idleJob: Job? = null
    private var closed = false
    private var consecutiveErrors = 0

    // Frame hand-off state, main thread only.
    private var rendered = false
    private var pendingImage: Image? = null
    private var waiter: CompletableDeferred<Image?>? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            rendered = true
            tryDeliver()
        }

        override fun onPlayerError(error: PlaybackException) {
            consecutiveErrors++
            waiter?.complete(null)
            releasePlayer() // recreated lazily on the next request
        }
    }

    override suspend fun frameAt(positionMs: Long): ImageBitmap? = withContext(Dispatchers.Main.immediate) {
        if (closed || consecutiveErrors >= MAX_ERRORS) return@withContext null
        val key = quantize(positionMs, GRID_MS)
        cache[key]?.let { return@withContext it }
        if (!allowed()) return@withContext null
        idleJob?.cancel()
        delay(DEBOUNCE_MS) // cancelled by the caller when the thumb moves on
        requestLock.withLock {
            cache[key]?.let { return@withLock it }
            if (closed || !allowed()) return@withLock null
            idleJob?.cancel()
            val image = grab(key) ?: return@withLock null
            try {
                withContext(Dispatchers.Default) { YuvConverter.toBitmap(image, MAX_OUT_WIDTH) }.asImageBitmap()
                    .also { cache.put(key, it); consecutiveErrors = 0 }
            } finally {
                image.close()
            }
        }
    }

    /** Seeks the preview player to [positionMs] and waits for the first frame rendered after that seek. */
    private suspend fun grab(positionMs: Long): Image? {
        val result = CompletableDeferred<Image?>()
        waiter = result
        rendered = false
        pendingImage?.close(); pendingImage = null
        val fresh = player == null
        val p = ensurePlayer(positionMs)
        if (!fresh) p.seekTo(positionMs)
        var delivered: Image? = null
        try {
            delivered = withTimeoutOrNull(FRAME_TIMEOUT_MS) { result.await() }
            return delivered
        } finally {
            if (waiter === result) waiter = null
            if (delivered == null) {
                // Timed out or cancelled: an image that raced in must not leak.
                if (result.isCompleted && !result.isCancelled) runCatching { result.getCompleted()?.close() }
                pendingImage?.close(); pendingImage = null
            }
        }
    }

    private fun tryDeliver() {
        val w = waiter ?: return
        val img = pendingImage ?: return
        if (!rendered || w.isCompleted) return
        pendingImage = null
        if (!w.complete(img)) img.close()
    }

    private fun ensurePlayer(startMs: Long): ExoPlayer {
        player?.let { return it }
        val h = renditionHeight.coerceIn(144, 480)
        val w = (h * 16 / 9) and 1.inv()
        val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: IllegalStateException) { null }
            if (img != null) {
                pendingImage?.close()
                pendingImage = img
                tryDeliver()
            }
        }, main)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheSources.forPlayback(headers)))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 3_000, 500, 1_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(640, 360)
            .setForceLowestBitrate(true)
            .build()
        p.addListener(listener)
        p.setVideoSurface(r.surface)
        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        reader = r
        player = p
        return p
    }

    private fun releasePlayer() {
        player?.let { it.removeListener(listener); it.clearVideoSurface(); it.release() }
        player = null
        reader?.let { it.setOnImageAvailableListener(null, null); it.close() }
        reader = null
        pendingImage = null // closed together with the reader
        rendered = false
    }

    override fun onScrubEnd() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_RELEASE_MS); releasePlayer() }
    }

    override fun close() {
        closed = true
        idleJob?.cancel()
        waiter?.complete(null)
        releasePlayer()
        cache.clear()
        scope.cancel()
    }

    companion object {
        const val GRID_MS = 5_000L
        const val DEBOUNCE_MS = 150L
        const val IDLE_RELEASE_MS = 2_000L
        const val FRAME_TIMEOUT_MS = 6_000L
        const val MAX_ERRORS = 3
        const val MAX_OUT_WIDTH = 480
        const val CACHE_BYTES = 8L * 1024 * 1024
    }
}

/** BT.709 limited-range YUV_420_888 -> ARGB_8888, nearest-neighbour subsampled so the output is at most [maxWidth] wide. */
internal object YuvConverter {
    fun toBitmap(image: Image, maxWidth: Int): Bitmap {
        val crop = image.cropRect
        val srcW = crop.width().takeIf { it > 0 } ?: image.width
        val srcH = crop.height().takeIf { it > 0 } ?: image.height
        val step = ((srcW + maxWidth - 1) / maxWidth).coerceAtLeast(1)
        val w = srcW / step
        val h = srcH / step
        val py = image.planes[0]; val pu = image.planes[1]; val pv = image.planes[2]
        val yb = py.buffer; val ub = pu.buffer; val vb = pv.buffer
        val out = IntArray(w * h)
        for (j in 0 until h) {
            val sy = crop.top + j * step
            val yRow = sy * py.rowStride
            val uRow = (sy shr 1) * pu.rowStride
            val vRow = (sy shr 1) * pv.rowStride
            for (i in 0 until w) {
                val sx = crop.left + i * step
                val y = (yb.get(yRow + sx * py.pixelStride).toInt() and 0xFF) - 16
                val u = (ub.get(uRow + (sx shr 1) * pu.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get(vRow + (sx shr 1) * pv.pixelStride).toInt() and 0xFF) - 128
                val c = 1192 * y.coerceAtLeast(0) // 1.164 * 1024
                val r = (c + 1836 * v) shr 10
                val g = (c - 218 * u - 546 * v) shr 10
                val b = (c + 2163 * u) shr 10
                out[j * w + i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
