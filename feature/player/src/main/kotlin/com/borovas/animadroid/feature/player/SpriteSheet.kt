package com.borovas.animadroid.feature.player

import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One thumbnail cue of a "thumbnails sprite" WebVTT: show the [w]x[h] region at ([x],[y]) of [imageUrl]
 * while the playback position is in [startMs, endMs).
 *
 * Survey of hianime/zokoanime embeds (Nov 2026, ~45 shows/movies, first+last episodes): `sprite_vtt` was non-empty for
 * exactly one (the movie "Your Name"). That one looked like
 *   https://hls.dramahot.top/v/<..>/sprite.vtt   (text/vtt, 40 KB, 639 cues, 10 s interval)
 *   00:00:00.000 --> 00:00:10.000 / sprite.jpg#xywh=0,0,160,90 ... 480,11430,160,90
 * i.e. a single 800x11520 JPEG (5 columns x 128 rows of 160x90 tiles, ~1.9 MB), cue URL relative to the VTT URL. Both
 * the VTT and the JPEG answer 403 without `Referer: https://zokoanime.video/` and 200 with it (cache-control immutable,
 * CORS *). So the sprite path is rare in practice and the decode fallback carries most videos.
 */
internal data class SpriteCue(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

internal object SpriteVtt {
    private val timing = Regex("""^\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val xywh = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""")

    private fun ms(h: String?, m: String, s: String, frac: String): Long =
        (h?.toLong() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + frac.padEnd(3, '0').toLong()

    /** Tolerant parser: BOM, CRLF/CR, optional cue ids, NOTE blocks, `HH:MM:SS.mmm` or `MM:SS.mmm`. Result is sorted by start. */
    fun parse(text: String, vttUrl: String): List<SpriteCue> {
        val lines = text.removePrefix("﻿").lines().map { it.trim('\r', ' ', '\t') }
        val cues = ArrayList<SpriteCue>()
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = ms(g[1].ifEmpty { null }, g[2], g[3], g[4])
            val end = ms(g[5].ifEmpty { null }, g[6], g[7], g[8])
            var j = i + 1
            while (j < lines.size && lines[j].isEmpty()) j++
            val payload = lines.getOrNull(j).orEmpty()
            val r = xywh.find(payload)
            if (r != null && end > start && !timing.containsMatchIn(payload)) {
                val img = payload.substringBefore('#').trim()
                if (img.isNotEmpty()) {
                    val (x, y, w, h) = r.groupValues.drop(1).map { it.toInt() }
                    if (w > 0 && h > 0) cues += SpriteCue(start, end, resolve(vttUrl, img), x, y, w, h)
                }
                i = j + 1
            } else {
                i = j.coerceAtLeast(i + 1)
            }
        }
        return cues.sortedBy { it.startMs }
    }

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else runCatching { URI(base).resolve(ref).toString() }.getOrElse { base.substringBeforeLast('/') + "/" + ref.trimStart('/') }

    /**
     * Cue covering [positionMs] ([start, end) so a boundary belongs to the next cue). Gaps use the previous cue,
     * positions before the first cue use the first, after the last use the last. Null only for an empty list.
     * [cues] must be sorted by start.
     */
    fun cueAt(cues: List<SpriteCue>, positionMs: Long): SpriteCue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return cues[ans]
    }

    /** Crop rect (left, top, right, bottom) clamped to an image of [imgW]x[imgH]; null when the tile lies outside it. */
    fun cropRect(cue: SpriteCue, imgW: Int, imgH: Int): IntArray? {
        val l = cue.x.coerceAtLeast(0)
        val t = cue.y.coerceAtLeast(0)
        val r = (cue.x + cue.w).coerceAtMost(imgW)
        val b = (cue.y + cue.h).coerceAtMost(imgH)
        return if (r > l && b > t) intArrayOf(l, t, r, b) else null
    }
}

/**
 * Preview frames cropped out of the provider's sprite sheet(s). The VTT is fetched lazily on first use; sprite images
 * are kept as encoded bytes behind a [BitmapRegionDecoder] (an 800x11520 sheet would be 36 MB decoded) in a small
 * size-capped LRU, and cropped tiles are cached too. All requests carry [headers] (Referer!).
 */
internal class SpriteSheetPreviewSource(
    private val client: OkHttpClient,
    private val vttUrl: String,
    private val headers: Map<String, String>,
) : ScrubPreviewSource {
    private class Sheet(val bytes: Int, val decoder: BitmapRegionDecoder)

    private val lock = Mutex()
    private var cues: List<SpriteCue>? = null
    private val sheets = LruMap<String, Sheet>(MAX_SHEET_BYTES, { it.bytes.toLong() }, { _, s -> runCatching { s.decoder.recycle() } })
    private val tiles = LruMap<SpriteCue, ImageBitmap>(MAX_TILES)

    @Volatile var failed = false
        private set

    override suspend fun frameAt(positionMs: Long): ImageBitmap? {
        if (failed) return null
        return try {
            lock.withLock {
                val list = cues ?: SpriteVtt.parse(fetchText(vttUrl), vttUrl).also { cues = it }
                val cue = SpriteVtt.cueAt(list, positionMs) ?: run { failed = true; return null }
                tiles[cue]?.let { return it }
                val sheet = sheets[cue.imageUrl] ?: loadSheet(cue.imageUrl).also { sheets.put(cue.imageUrl, it) }
                val rect = SpriteVtt.cropRect(cue, sheet.decoder.width, sheet.decoder.height) ?: return null
                val bmp = withContext(Dispatchers.Default) {
                    sheet.decoder.decodeRegion(Rect(rect[0], rect[1], rect[2], rect[3]), null)
                } ?: return null
                bmp.asImageBitmap().also { tiles.put(cue, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 403 / offline / malformed: remember so the composite switches to the fallback instead of retrying per frame.
            failed = true
            null
        }
    }

    private suspend fun loadSheet(url: String): Sheet {
        val bytes = fetch(url, MAX_SHEET_BYTES.toInt())
        val decoder = withContext(Dispatchers.Default) {
            @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: throw IOException("Undecodable sprite image")
        return Sheet(bytes.size, decoder)
    }

    private suspend fun fetchText(url: String): String = fetch(url, MAX_VTT_BYTES).decodeToString()

    private suspend fun fetch(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(req)
        val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                val body = resp.body
                if (body.contentLength() > maxBytes) throw IOException("Too large: ${body.contentLength()}")
                val data = body.bytes()
                if (data.size > maxBytes) throw IOException("Too large: ${data.size}")
                data
            }
        } finally {
            handle?.dispose()
        }
    }

    override fun close() {
        // Decoders are recycled by the LRU callback; skip if a request holds the lock (it then ends with the scope).
        if (lock.tryLock()) {
            try { sheets.clear(); tiles.clear() } finally { lock.unlock() }
        }
    }

    private companion object {
        const val MAX_SHEET_BYTES = 12L * 1024 * 1024
        const val MAX_VTT_BYTES = 4 * 1024 * 1024
        const val MAX_TILES = 64L
    }
}
