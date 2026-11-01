package com.borovas.animadroid.feature.subtitles

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeEngine(
    var missing: List<String> = emptyList(),
    var failTranslate: Boolean = false,
    val unsupported: Set<String> = emptySet(),
) : TranslationEngine {
    val calls = mutableListOf<List<String>>()
    var prepared = 0
    override fun isSupported(language: String) = language !in unsupported
    override suspend fun missingModels(source: String, target: String) = missing
    override suspend fun prepare(source: String, target: String) { prepared++; missing = emptyList() }
    override suspend fun translate(source: String, target: String, texts: List<String>): List<String> {
        if (failTranslate) throw TranslationFailedException("boom")
        calls += texts
        return texts.map { "[$target]$it" }
    }
    override fun close() = Unit
}

private class MapCache : TranslationCache {
    val map = HashMap<String, List<SubtitleCue>>()
    override fun get(key: String) = map[key]
    override fun put(key: String, cues: List<SubtitleCue>) { map[key] = cues }
}

class SubtitleTranslatorTest {
    private val vtt = buildString {
        append("WEBVTT\n\n")
        for (i in 0 until 5) append("00:00:0$i.000 --> 00:00:0$i.800\n${if (i == 3) "<i>Hello</i>" else "Line $i"}\n\n")
        append("00:00:09.000 --> 00:00:09.500\nLine 1\n\n") // duplicate body
    }
    private val req = TranslationRequest("ep1", "https://x/en.vtt", emptyMap(), "en", "zh")
    private fun translator(engine: TranslationEngine, cache: TranslationCache = MapCache(), metered: Boolean = false, batch: Int = 2) =
        SubtitleTranslator(engine, { _, _ -> vtt }, cache, { metered }, batchSize = batch)

    @Test fun translatesTextAndKeepsTimingAndTags() = runTest {
        val out = translator(FakeEngine()).translate(req) { 0 }.toList()
        val done = out.last() as TranslationProgress.Done
        assertEquals(6, done.cues.size)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L, 9000L), done.cues.map { it.startMs })
        assertEquals(800L, done.cues[0].endMs)
        assertEquals("[zh]Line 0", done.cues[0].text)
        assertEquals("<i>[zh]Hello</i>", done.cues[3].text)
        assertTrue(out.any { it is TranslationProgress.Translating && it.done in 1 until 6 }) // partial results stream out
    }

    @Test fun duplicateBodiesAreTranslatedOnce() = runTest {
        val e = FakeEngine()
        translator(e).translate(req) { 0 }.toList()
        assertEquals(1, e.calls.flatten().count { it == "Line 1" })
    }

    @Test fun cacheServesSecondRunWithoutEngine() = runTest {
        val cache = MapCache()
        translator(FakeEngine(), cache).translate(req) { 0 }.toList()
        val e2 = FakeEngine()
        val out = translator(e2, cache).translate(req) { 0 }.toList()
        assertTrue(out.last() is TranslationProgress.Done)
        assertTrue(e2.calls.isEmpty())
    }

    @Test fun startsWithBatchNearPlaybackPosition() = runTest {
        val e = FakeEngine()
        translator(e).translate(req) { 3_500 }.toList()
        assertEquals(listOf("Line 2", "Hello"), e.calls.first())
    }

    @Test fun meteredWithMissingPackNeedsConfirmation() = runTest {
        try {
            translator(FakeEngine(missing = listOf("zh")), metered = true).translate(req) { 0 }.toList()
            fail("expected MeteredDownloadRequired")
        } catch (e: MeteredDownloadRequired) {
            assertEquals(listOf("zh"), e.languages)
        }
        // confirmed: downloads and proceeds
        val engine = FakeEngine(missing = listOf("zh"))
        val out = translator(engine, metered = true).translate(req.copy(allowMetered = true)) { 0 }.toList()
        assertTrue(out.contains(TranslationProgress.DownloadingModel))
        assertEquals(1, engine.prepared)
    }

    @Test fun unmeteredDownloadsWithoutAsking() = runTest {
        val engine = FakeEngine(missing = listOf("zh"))
        translator(engine, metered = false).translate(req) { 0 }.toList()
        assertEquals(1, engine.prepared)
    }

    @Test fun unsupportedLanguageFails() = runTest {
        try {
            translator(FakeEngine(unsupported = setOf("zh"))).translate(req) { 0 }.toList()
            fail()
        } catch (e: UnsupportedLanguageException) {
            assertEquals("zh", e.language)
        }
    }

    @Test fun engineFailurePropagatesAndNothingIsCached() = runTest {
        val cache = MapCache()
        try {
            translator(FakeEngine(failTranslate = true), cache).translate(req) { 0 }.toList()
            fail()
        } catch (e: TranslationFailedException) {
            assertTrue(cache.map.isEmpty())
        }
    }

    @Test fun emptyTrackFails() = runTest {
        val t = SubtitleTranslator(FakeEngine(), { _, _ -> "WEBVTT\n" }, MapCache(), { false })
        try { t.translate(req) { 0 }.toList(); fail() } catch (_: TranslationFailedException) { }
    }

    @Test fun diskCacheRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("subs").toFile()
        try {
            val cues = listOf(SubtitleCue(1, 2, "你好"))
            LayeredTranslationCache(dir).put("k", cues)
            assertEquals(cues, LayeredTranslationCache(dir).get("k"))
        } finally { dir.deleteRecursively() }
    }
}
