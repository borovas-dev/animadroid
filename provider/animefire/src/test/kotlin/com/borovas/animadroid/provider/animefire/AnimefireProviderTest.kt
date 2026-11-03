package com.borovas.animadroid.provider.animefire

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.ProviderException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnimefireProviderTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private fun origin() = server.url("/").toString().trimEnd('/')
    private fun provider(base: () -> String = ::origin, mirrors: List<String>? = null) = AnimefireProvider(OkHttpClient()) {
        ProviderRemoteConfig(baseUrls = mirrors ?: listOf(base()), params = mapOf("apiUrl" to if (mirrors == null) base() else origin()))
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val p = request.url.encodedPath
                fun ok(b: String) = MockResponse.Builder().code(200).body(b).build()
                return when {
                    p == "/animes/pesquisar" -> ok(res("search.json"))
                    p == "/anime/naruto" -> ok(res("anime.json"))
                    p == "/episode/e1" -> ok(res("episode.json").replace("BASE", origin()))
                    p.endsWith("/master.m3u8") -> ok(res("master.m3u8"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun metadata() {
        val p = provider()
        assertEquals("animefire", p.id); assertEquals(setOf("pt"), p.contentLanguages)
        assertEquals(0, p.priority); assertEquals(false, p.gfwBlocked)
    }

    @Test fun search() = runTest {
        val r = provider().search("naruto", TranslationMode.SUB)
        assertEquals(listOf("naruto", "naruto-shippuden"), r.map { it.id })
        assertEquals("Naruto Shippuden", r[1].title)
        assertEquals("naruto", seen.first().url.queryParameter("q"))
        assertEquals("3", seen.first().url.queryParameter("v"))
        assertEquals("Naruto", r[0].title)
    }

    @Test fun episodesGlobalNumbering() = runTest {
        val eps = provider().episodes(Anime("naruto", "animefire", "Naruto"), TranslationMode.SUB)
        assertEquals(listOf("1", "2", "3"), eps.map { it.number })
        assertEquals(listOf("e1", "e2", "e3"), eps.map { it.id })
    }

    @Test fun subStreamsAreHardsubPt() = runTest {
        val s = provider().streams(Anime("naruto", "animefire", "N"), Episode("e1", "1"), TranslationMode.SUB)
        assertEquals("pt", s.hardsubLanguage)
        assertTrue(s.masterUrl!!.endsWith("/hls/sub/master.m3u8"))
        assertEquals(listOf(1080, 360), s.variants.map { it.quality })
    }

    @Test fun dubStreams() = runTest {
        val s = provider().streams(Anime("naruto", "animefire", "N"), Episode("e1", "1"), TranslationMode.DUB)
        assertNull(s.hardsubLanguage)
        assertTrue(s.masterUrl!!.endsWith("/hls/dub/master.m3u8"))
    }

    @Test fun missingDubThrows() = runTest {
        val body = res("episode.json").replace("\"dublado\"", "\"x\"").replace("BASE", origin())
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body(body).build() }
        try { provider().streams(Anime("naruto", "animefire", "N"), Episode("e1", "1"), TranslationMode.DUB); fail() } catch (_: ProviderException) {}
    }

    @Test fun baseUrlIsReadPerRequest() = runTest {
        var base = "http://127.0.0.1:1"
        val p = provider(base = { base })
        try { p.search("naruto", TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        base = origin()
        assertTrue(p.search("naruto", TranslationMode.SUB).isNotEmpty())
    }
}
