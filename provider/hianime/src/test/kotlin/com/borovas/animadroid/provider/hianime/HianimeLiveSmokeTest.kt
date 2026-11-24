package com.borovas.animadroid.provider.hianime

import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits the real site; skipped unless LIVE_TESTS=1 (see .github/workflows/live-smoke.yml). */
class HianimeLiveSmokeTest {
    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_TESTS=1 to hit the network", System.getenv("LIVE_TESTS") == "1")

    @Test fun searchEpisodesStreams() = runBlocking {
        val p = HianimeProvider(OkHttpClient())
        val anime = p.search("one piece", TranslationMode.SUB).first()
        println(anime)
        val eps = p.episodes(anime, TranslationMode.SUB)
        assertTrue(eps.isNotEmpty())
        val s = p.streams(anime, eps.first(), TranslationMode.SUB)
        println(s)
        assertTrue(s.variants.isNotEmpty())
    }
}
