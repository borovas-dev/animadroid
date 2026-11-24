package com.borovas.animadroid.provider.kickassanime

import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Hits the real site; skipped unless LIVE_TESTS=1 (see .github/workflows/live-smoke.yml). */
class KickAssAnimeLiveSmokeTest {
    @Before fun onlyWhenRequested() = assumeTrue("set LIVE_TESTS=1 to hit the network", System.getenv("LIVE_TESTS") == "1")

    @Test fun searchEpisodesStreams() = runBlocking {
        val p = KickAssAnimeProvider(OkHttpClient())
        for (mode in TranslationMode.values()) {
            val anime = p.search("frieren", mode).first()
            val eps = p.episodes(anime, mode)
            assertTrue(eps.isNotEmpty())
            val s = p.streams(anime, eps.first(), mode)
            println("$mode $s")
            assertTrue(s.variants.isNotEmpty() && s.externalAudioUrl != null)
        }
    }
}
