package com.borovas.animadroid.feature.dubbing

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

fun wavBytes(durationMs: Long, rate: Int = 1000, channels: Int = 1, bogusSizes: Boolean = false): ByteArray {
    val frames = (durationMs * rate / 1000).toInt()
    val data = ByteArray(frames * channels * 2)
    val o = ByteArrayOutputStream()
    fun i32(v: Long) { for (s in 0..3) o.write(((v shr (8 * s)) and 0xff).toInt()) }
    fun i16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
    o.write("RIFF".toByteArray()); i32(if (bogusSizes) 0xFFFFFFFFL else 36L + data.size); o.write("WAVE".toByteArray())
    o.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels); i32(rate.toLong()); i32(rate.toLong() * channels * 2); i16(channels * 2); i16(16)
    o.write("data".toByteArray()); i32(if (bogusSizes) 0xFFFFFFFFL else data.size.toLong()); o.write(data)
    return o.toByteArray()
}

class FitAndWavTest {
    @Test fun fits() = assertEquals(FitResult.Play(1f), fitSpeed(900, 1000))
    @Test fun exactFit() = assertEquals(FitResult.Play(1f), fitSpeed(1000, 1000))
    @Test fun speedUp() = assertEquals(FitResult.Play(1.2f), fitSpeed(1200, 1000))
    @Test fun atMaxSpeed() = assertEquals(FitResult.Play(1.35f), fitSpeed(1350, 1000))
    @Test fun overflow() = assertEquals(FitResult.PlayOverflow(1.35f), fitSpeed(1600, 1000))
    @Test fun overflowEdge() = assertEquals(FitResult.PlayOverflow(1.35f), fitSpeed(1800, 1000))
    @Test fun drop() = assertEquals(FitResult.Drop, fitSpeed(1900, 1000))
    @Test fun degenerate() {
        assertEquals(FitResult.Drop, fitSpeed(1000, 0))
        assertEquals(FitResult.Drop, fitSpeed(0, 1000))
    }

    @Test fun wavDuration() {
        val w = WavReader.parse(wavBytes(1500, rate = 16000))!!
        assertEquals(1500L, w.durationMs)
        assertEquals(1, w.channelCount)
        assertEquals(1500L, WavReader.parse(wavBytes(1500, rate = 22050, channels = 2))!!.let { it.durationMs })
    }
    @Test fun wavBogusStreamingSizes() = assertEquals(700L, WavReader.parse(wavBytes(700, bogusSizes = true))!!.durationMs)
    @Test fun wavRejectsGarbage() {
        assertNull(WavReader.parse(ByteArray(5)))
        assertNull(WavReader.parse(ByteArray(64)))
        assertNull(WavReader.parse(wavBytes(100).copyOf(30)))
    }

    @Test fun availabilityMapping() {
        assertEquals(DubAvailability.Ready, availabilityFromCode(TextToSpeech.LANG_AVAILABLE))
        assertEquals(DubAvailability.Ready, availabilityFromCode(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertEquals(DubAvailability.Ready, availabilityFromCode(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
        assertEquals(DubAvailability.NeedsVoiceData, availabilityFromCode(TextToSpeech.LANG_MISSING_DATA))
        assertEquals(DubAvailability.Unsupported, availabilityFromCode(TextToSpeech.LANG_NOT_SUPPORTED))
        assertEquals(DubAvailability.NeedsVoiceData, availabilityFromCode(TextToSpeech.LANG_AVAILABLE, listOf(VoiceState(false, true), VoiceState(true, false))))
        assertEquals(DubAvailability.Ready, availabilityFromCode(TextToSpeech.LANG_AVAILABLE, listOf(VoiceState(false, true), VoiceState(true, true))))
    }

    @Test fun speakable() {
        assertTrue(isSpeakable("Hello"))
        assertTrue(isSpeakable("42"))
        assertEquals(false, isSpeakable("♪ ♪"))
        assertEquals(false, isSpeakable("  "))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DubSchedulerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSynth(val durations: Map<String, Long>) : TtsSynthesizer {
        val calls = mutableListOf<String>()
        override suspend fun availability(languageTag: String) = DubAvailability.Ready
        override suspend fun synthesize(languageTag: String, text: String, out: File): Boolean {
            calls += text
            delay(10)
            out.parentFile?.mkdirs()
            out.writeBytes(wavBytes(durations[text] ?: 500))
            return true
        }
    }

    private class Played(val atMs: Long, val durationMs: Long, val speed: Float)
    private class FakeSink(val scope: TestScope) : AudioSink {
        val played = mutableListOf<Played>()
        var cancelled = 0
        override suspend fun play(wav: WavData, speed: Float) {
            played += Played(scope.currentTime, wav.durationMs, speed)
            try { delay((wav.durationMs / speed).toLong()) } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
        }
        override fun stop() {}
    }

    private class Harness(val t: TestScope, dir: File, durations: Map<String, Long>, cueList: List<DubCue>, ahead: Long = 45_000) {
        val synth = FakeSynth(durations)
        val sink = FakeSink(t)
        val cues = MutableStateFlow(cueList)
        var offset = 0L
        var isPlaying = true
        val duck = mutableListOf<Boolean>()
        val scheduler = DubScheduler(synth, sink, dir, aheadMs = ahead)
        fun pos() = t.currentTime + offset
        fun begin() = scheduler.start(t.backgroundScope, "pl", cues, ::pos, { isPlaying }, { duck += it })
    }

    @Test fun playsInOrderAtCueTimeAndDucks() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 800, "b" to 800), listOf(DubCue(5000, 6000, "a"), DubCue(2000, 3000, "b")))
        h.begin()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, h.sink.played.size)
        assertEquals(listOf(800L, 800L), h.sink.played.map { it.durationMs })
        assertTrue(h.sink.played[0].atMs in 1900..2100)   // "b" first (start 2000, minus latency)
        assertTrue(h.sink.played[1].atMs in 4900..5100)
        assertEquals(listOf(true, false, true, false), h.duck)
        assertEquals(1f, h.sink.played[0].speed)
        h.scheduler.stop()
    }

    @Test fun speedsUpLongSpeech() = runTest {
        // window = 1000 + gap 0 (next cue starts at end) -> 1200 ms speech => 1.2x
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 1200, "b" to 500), listOf(DubCue(1000, 2000, "a"), DubCue(2000, 3000, "b")))
        h.begin(); advanceTimeBy(5000); runCurrent()
        assertEquals(1.2f, h.sink.played[0].speed, 0.001f)
    }

    @Test fun gapExtendsWindow() = runTest {
        // 1300 ms speech, 1000 window + 1500 cap gap (next cue 5s later) -> fits at 1x
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 1300), listOf(DubCue(1000, 2000, "a"), DubCue(9000, 9500, "x")))
        h.begin(); advanceTimeBy(3000); runCurrent()
        assertEquals(1f, h.sink.played[0].speed, 0.001f)
    }

    @Test fun dropsHopelessCues() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 5000), listOf(DubCue(1000, 2000, "a")))
        h.begin(); advanceTimeBy(8000); runCurrent()
        assertTrue(h.sink.played.isEmpty())
        assertTrue(h.duck.isEmpty())
    }

    @Test fun skipsBlankAndMusicCues() = runTest {
        val h = Harness(this, tmp.newFolder(), emptyMap(), listOf(DubCue(1000, 2000, "♪"), DubCue(3000, 4000, " "), DubCue(5000, 6000, "ok")))
        h.begin(); advanceTimeBy(8000); runCurrent()
        assertEquals(listOf("ok"), h.synth.calls)
        assertEquals(1, h.sink.played.size)
    }

    @Test fun skipsCuesWeAreTooLateFor() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 500, "b" to 500), listOf(DubCue(0, 3000, "a"), DubCue(20_000, 23_000, "b")))
        h.begin()
        advanceTimeBy(100); runCurrent()
        h.offset = 20_000 + 2_000 // jump (no onSeek) far past b's start (>1.2s late)
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, h.sink.played.size) // only "a"
    }

    @Test fun preGenerationWindow() = runTest {
        val h = Harness(this, tmp.newFolder(), emptyMap(), listOf(DubCue(0, 1000, "a"), DubCue(40_000, 41_000, "b"), DubCue(60_000, 61_000, "c")))
        h.isPlaying = false
        h.begin(); advanceTimeBy(2000); runCurrent()
        assertEquals(listOf("a", "b"), h.synth.calls)
        h.offset = 20_000 // c now within 45 s
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf("a", "b", "c"), h.synth.calls)
    }

    @Test fun cachedFilesAreReused() = runTest {
        val dir = tmp.newFolder()
        val h = Harness(this, dir, emptyMap(), listOf(DubCue(0, 1000, "a")))
        h.isPlaying = false
        h.begin(); advanceTimeBy(1000); runCurrent()
        h.scheduler.stop()
        val h2 = Harness(this, dir, emptyMap(), listOf(DubCue(0, 1000, "a")))
        h2.isPlaying = false
        h2.begin(); advanceTimeBy(1000); runCurrent()
        assertTrue(h2.synth.calls.isEmpty())
        assertNotNull(h2.scheduler.cacheFile("pl", "a").takeIf { it.exists() })
        h2.scheduler.clearCache()
        assertEquals(false, h2.scheduler.cacheFile("pl", "a").exists())
    }

    @Test fun pauseStopsPlaybackAndNoTicking() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 3000), listOf(DubCue(1000, 5000, "a")))
        h.begin(); advanceTimeBy(1500); runCurrent()
        assertEquals(1, h.sink.played.size)
        h.isPlaying = false
        advanceTimeBy(200); runCurrent()
        assertEquals(1, h.sink.cancelled)
        assertEquals(listOf(true, false), h.duck)
    }

    @Test fun seekReschedulesFromNewPosition() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 800, "b" to 800), listOf(DubCue(1000, 3000, "a"), DubCue(30_000, 32_000, "b")))
        h.begin(); advanceTimeBy(1300); runCurrent()
        assertEquals(1, h.sink.played.size) // "a" mid-utterance
        h.offset = 28_000 - currentTime + 0 // pos ~ 28_000 -> seek forward near b
        h.scheduler.onSeek()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, h.sink.cancelled)
        assertEquals(2, h.sink.played.size)
        assertEquals(800L, h.sink.played[1].durationMs)
        assertEquals(listOf(true, false, true, false), h.duck)
        // seek back replays "a"
        h.offset = -currentTime + 900
        h.scheduler.onSeek()
        advanceTimeBy(1500); runCurrent()
        assertEquals(3, h.sink.played.size)
        h.scheduler.stop()
    }

    @Test fun stopReleasesDuck() = runTest {
        val h = Harness(this, tmp.newFolder(), mapOf("a" to 3000), listOf(DubCue(1000, 5000, "a")))
        h.begin(); advanceTimeBy(1500); runCurrent()
        h.scheduler.stop(); runCurrent()
        assertEquals(listOf(true, false), h.duck)
    }

    @Test fun speakableTextStripsMarkupAndSymbols() {
        assertEquals("Hello there", speakableText("*Hello there*"))
        assertEquals("Hello world", speakableText("<i>Hello</i> {\\an8}world"))
        assertEquals("I'm fine", speakableText("[sighs] I'm fine"))
        assertEquals("Hi Who is it", speakableText("- Hi\n- Who is it"))
        assertEquals("la la", speakableText("\u266A la la \u266A"))
        assertEquals("", speakableText("* * *"))
        assertEquals("Wait... what?", speakableText("Wait... what?"))
        assertEquals("snake case", speakableText("snake_case"))
    }
}
