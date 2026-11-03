package com.borovas.animadroid.feature.settings

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun streamSet(master: String? = null) = StreamSet(master, emptyList(), emptyMap())

private class FakeProvider(
    val onSearch: suspend () -> List<Anime> = { listOf(Anime("a", "fake", "Naruto")) },
    val onEpisodes: suspend () -> List<Episode> = { listOf(Episode("e1", "1")) },
    val onStreams: suspend () -> StreamSet = { streamSet("https://x/m.m3u8") },
) : AnimeProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override val contentLanguages = setOf("en")
    override suspend fun search(query: String, mode: TranslationMode) = onSearch()
    override suspend fun episodes(anime: Anime, mode: TranslationMode) = onEpisodes()
    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode) = onStreams()
}

class HealthRunnerTest {
    private val init = ProviderHealth("fake", "Fake")

    @Test fun allStepsOkWithLatency() = runTest {
        val runner = HealthRunner(timeoutMs = 5_000, now = { currentTime })
        val h = runner.check(FakeProvider(onSearch = { delay(120); listOf(Anime("a", "fake", "Naruto")) }), init)
        assertTrue(h.allOk)
        assertEquals(120L, h.search.millis)
    }

    @Test fun searchFailureSkipsRest() = runTest {
        val h = HealthRunner(now = { currentTime }).check(FakeProvider(onSearch = { error("boom") }), init)
        assertEquals(StepStatus.FAILED, h.search.status)
        assertEquals("boom", h.search.message)
        assertEquals(StepStatus.SKIPPED, h.episodes.status)
        assertEquals(StepStatus.SKIPPED, h.streams.status)
    }

    @Test fun emptySearchIsFailure() = runTest {
        val h = HealthRunner().check(FakeProvider(onSearch = { emptyList() }), init)
        assertEquals(StepStatus.FAILED, h.search.status)
    }

    @Test fun episodesFailureSkipsStreams() = runTest {
        val h = HealthRunner().check(FakeProvider(onEpisodes = { throw java.io.IOException("net down") }), init)
        assertEquals(StepStatus.OK, h.search.status)
        assertEquals(StepStatus.FAILED, h.episodes.status)
        assertEquals(StepStatus.SKIPPED, h.streams.status)
    }

    @Test fun streamTimeoutReported() = runTest {
        val h = HealthRunner(timeoutMs = 1_000, now = { currentTime }).check(FakeProvider(onStreams = { delay(60_000); streamSet() }), init)
        assertEquals(StepStatus.FAILED, h.streams.status)
        assertTrue(h.streams.message!!.contains("timed out"))
        assertEquals(1_000L, h.streams.millis)
    }

    @Test fun noPlayableStreamsIsFailure() = runTest {
        val h = HealthRunner().check(FakeProvider(onStreams = { streamSet() }), init)
        assertEquals(StepStatus.FAILED, h.streams.status)
    }

    @Test fun reportsProgressUpdates() = runTest {
        val seen = ArrayList<ProviderHealth>()
        HealthRunner().check(FakeProvider(), init) { seen += it }
        assertTrue(seen.size >= 4)
        assertTrue(seen.last().finished)
    }
}
