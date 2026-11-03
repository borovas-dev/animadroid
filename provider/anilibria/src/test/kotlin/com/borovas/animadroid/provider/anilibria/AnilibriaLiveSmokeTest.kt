package com.borovas.animadroid.provider.anilibria

import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Hits the real API; remove @Ignore to run manually. */
@Ignore("live network")
class AnilibriaLiveSmokeTest {
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
