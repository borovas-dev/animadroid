package com.borovas.animadroid.provider.animefire

import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Hits the real site; remove @Ignore to run manually. */
@Ignore("live network")
class AnimefireLiveSmokeTest {
    @Test fun searchEpisodesStreams() = runBlocking {
        val p = AnimefireProvider(OkHttpClient())
        val anime = p.search("naruto", TranslationMode.SUB).first()
        val eps = p.episodes(anime, TranslationMode.SUB)
        assertTrue(eps.isNotEmpty())
        val s = p.streams(anime, eps.first(), TranslationMode.SUB)
        println(s)
        assertTrue(s.variants.isNotEmpty())
    }
}
