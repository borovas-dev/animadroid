package com.borovas.animadroid.provider.anilibria

import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits the real API; skipped unless LIVE_TESTS=1 (see .github/workflows/live-smoke.yml). */
class AnilibriaLiveSmokeTest {
    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_TESTS=1 to hit the network", System.getenv("LIVE_TESTS") == "1")

    @Test fun searchEpisodesStreams() = runBlocking {
        val p = AnilibriaProvider(OkHttpClient())
        val anime = p.search("naruto", TranslationMode.SUB).first()
        println(anime)
        val eps = p.episodes(anime, TranslationMode.SUB)
        assertTrue(eps.isNotEmpty())
        val s = p.streams(anime, eps.first(), TranslationMode.SUB)
        println(s)
        assertTrue(s.variants.isNotEmpty())
    }
}
