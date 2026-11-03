package com.borovas.animadroid.feature.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val TAGS = Regex("""<[^>]*>|\{[^}]*\}""")
private val SOUND_NOTES = Regex("""\[[^\]]*]""")
private val SYMBOLS = Regex("""[*_~#^|\\`<>\u266A-\u266F\u00B6\u2022]""")
private val LEADING_DASH = Regex("""(^|\n)\s*[-\u2013\u2014]+\s*""")

/**
 * What the TTS should actually say for a subtitle cue: strips markup (`<i>`, ASS `{\an8}`), bracketed sound/speaker notes
 * (`[door slams]`), formatting symbols (`*`, `_`, `#`, ...), music notes and leading dialogue dashes, which engines would
 * otherwise read aloud ("asterisk", "hash", "dash"). Returns blank when nothing pronounceable is left.
 */
fun speakableText(raw: String): String =
    raw.replace(TAGS, " ").replace(SOUND_NOTES, " ").replace(LEADING_DASH, "$1").replace(SYMBOLS, " ")
        .replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()

/** True when [text] has something pronounceable (not blank, not just symbols such as music notes). */
fun isSpeakable(text: String) = text.any { it.isLetterOrDigit() }

/**
 * Pure scheduling logic: pre-generates speech for upcoming cues and plays each at its time. Uses only coroutine delays and the
 * [TtsSynthesizer]/[AudioSink] seams, so it runs under virtual time in JVM tests.
 */
class DubScheduler(
    private val synth: TtsSynthesizer,
    private val sink: AudioSink,
    private val cacheDir: File,
    private val tickMs: Long = 50,
    private val latencyMs: Long = 80,
    private val aheadMs: Long = 45_000,
    private val maxLateMs: Long = 1_200,
    private val maxGapExtensionMs: Long = 1_500,
    private val maxSpeed: Float = 1.35f,
) {
    private val lock = Any()
    private var root: Job? = null
    private var ducked: AtomicBoolean = AtomicBoolean(false)
    private var onDuckCb: ((Boolean) -> Unit)? = null
    private val seekRequested = AtomicBoolean(false)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun cacheFile(tag: String, text: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest("$tag\n$text".toByteArray())
        return File(cacheDir, h.take(16).joinToString("") { "%02x".format(it) } + ".wav")
    }

    fun start(
        scope: CoroutineScope,
        tag: String,
        cues: StateFlow<List<DubCue>>,
        positionMs: () -> Long,
        playing: () -> Boolean,
        onDuck: (Boolean) -> Unit,
    ) = synchronized(lock) {
        stopLocked()
        failed.clear()
        seekRequested.set(false)
        val duck = AtomicBoolean(false)
        ducked = duck
        onDuckCb = onDuck
        val sortedCues = SortedView(cues)
        root = scope.launch {
            launch { pregenerate(tag, sortedCues, positionMs) }
            launch { playbackLoop(this, tag, sortedCues, positionMs, playing, duck, onDuck) }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        root?.cancel()
        root = null
        sink.stop()
        if (ducked.compareAndSet(true, false)) onDuckCb?.invoke(false)
        onDuckCb = null
    }

    fun onSeek() { seekRequested.set(true) }

    fun clearCache() {
        failed.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private class SortedView(private val flow: StateFlow<List<DubCue>>) {
        private var src: List<DubCue>? = null
        private var sorted: List<DubCue> = emptyList()
        fun get(): List<DubCue> {
            val cur = flow.value
            if (cur !== src) { sorted = cur.map { it.copy(text = speakableText(it.text)) }.sortedBy { it.startMs }; src = cur }
            return sorted
        }
    }

    private suspend fun pregenerate(tag: String, cues: SortedView, positionMs: () -> Long) {
        cacheDir.mkdirs()
        while (true) {
            val pos = positionMs()
            val next = cues.get().firstOrNull { c ->
                c.endMs > pos && c.startMs <= pos + aheadMs && isSpeakable(c.text) &&
                    !cacheFile(tag, c.text).exists() && cacheFile(tag, c.text).name !in failed
            }
            if (next == null) { delay(250); continue }
            val out = cacheFile(tag, next.text)
            val ok = try { synth.synthesize(tag, next.text, out) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!ok) { failed.add(out.name); out.delete() }
        }
    }

    private suspend fun playbackLoop(
        scope: CoroutineScope, tag: String, cues: SortedView, positionMs: () -> Long, playing: () -> Boolean,
        ducked: AtomicBoolean, onDuck: (Boolean) -> Unit,
    ) {
        val spoken = HashSet<DubCue>()
        var current: Job? = null
        suspend fun cancelCurrent() { current?.cancelAndJoin(); current = null }
        try {
            while (scope.isActive) {
                if (seekRequested.getAndSet(false)) { cancelCurrent(); spoken.clear() }
                if (!playing()) { cancelCurrent(); delay(tickMs); continue }
                if (current?.isActive == true) { delay(tickMs); continue }
                val pos = positionMs()
                val list = cues.get()
                for ((i, cue) in list.withIndex()) {
                    if (cue.startMs - latencyMs > pos) break
                    if (pos > cue.startMs + maxLateMs || pos >= cue.endMs || cue in spoken || !isSpeakable(cue.text)) continue
                    val file = cacheFile(tag, cue.text)
                    if (!file.exists()) continue // may still become ready within the late allowance
                    spoken.add(cue)
                    val wav = try { WavReader.parse(file.readBytes()) } catch (_: Exception) { null }
                    if (wav == null) { file.delete(); continue }
                    val nextStart = list.getOrNull(i + 1)?.startMs
                    val gap = if (nextStart == null) maxGapExtensionMs else (nextStart - cue.endMs).coerceIn(0, maxGapExtensionMs)
                    val late = (pos - cue.startMs).coerceAtLeast(0)
                    val window = cue.endMs - cue.startMs + gap - late
                    val speed = when (val r = fitSpeed(wav.durationMs, window, maxSpeed)) {
                        is FitResult.Play -> r.speed
                        is FitResult.PlayOverflow -> r.speed
                        FitResult.Drop -> continue
                    }
                    current = scope.launch {
                        ducked.set(true); onDuck(true)
                        try {
                            sink.play(wav, speed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // playback failure: drop this cue
                        } finally {
                            if (ducked.compareAndSet(true, false)) onDuck(false)
                        }
                    }
                    break
                }
                delay(tickMs)
            }
        } finally {
            current?.cancel()
        }
    }
}
