package com.borovas.animadroid.provider.anilibria

import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.provider.api.ProviderException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnilibriaProviderTest {
    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun origin() = server.url("/").toString().trimEnd('/')
    private fun provider(base: () -> String = ::origin, mirrors: List<String>? = null) =
        AnilibriaProvider(OkHttpClient()) { ProviderRemoteConfig(baseUrls = mirrors ?: listOf(base())) }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "/api/v1/app/search/releases" -> res("search.json")
                    "/api/v1/anime/releases/413" -> res("release.json")
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun metadata() {
        val p = provider()
        assertEquals("anilibria", p.id); assertEquals(setOf("ru"), p.contentLanguages)
        assertEquals(0, p.priority); assertEquals(false, p.gfwBlocked)
    }

    @Test fun searchMapsFields() = runTest {
        val a = provider().search("naruto", TranslationMode.SUB).first()
        assertEquals("413", a.id); assertEquals("anilibria", a.providerId)
        assertEquals(1735, a.malId); assertEquals(2007, a.year); assertEquals(450, a.subEpisodes)
        assertTrue(a.coverUrl!!.startsWith("http://") && a.coverUrl!!.contains("/storage/releases/posters/413/"))
    }

    @Test fun dubIsEmpty() = runTest {
        val p = provider()
        assertTrue(p.search("naruto", TranslationMode.DUB).isEmpty())
        val a = p.search("naruto", TranslationMode.SUB).first()
        assertTrue(p.episodes(a, TranslationMode.DUB).isEmpty())
        try { p.streams(a, p.episodes(a, TranslationMode.SUB).first(), TranslationMode.DUB); fail() } catch (_: ProviderException) {}
    }

    @Test fun episodesSortedAndStreams() = runTest {
        val p = provider()
        val a = p.search("naruto", TranslationMode.SUB).first()
        val eps = p.episodes(a, TranslationMode.SUB)
        assertEquals(listOf("1", "370"), eps.map { it.number })
        val s1 = p.streams(a, eps[0], TranslationMode.SUB)
        assertEquals(listOf(1080, 720, 480), s1.variants.map { it.quality })
        assertEquals(s1.variants.first().url, s1.masterUrl)
        assertEquals(1735, s1.malId)
        assertEquals(1.0, s1.intro!!.startSec, 0.0); assertEquals(100.0, s1.intro!!.endSec, 0.0)
        assertEquals(1300.0, s1.outro!!.startSec, 0.0)
        assertNotNull(s1.headers["Referer"])
        val s2 = p.streams(a, eps[1], TranslationMode.SUB)
        assertEquals(listOf(720, 480), s2.variants.map { it.quality })
        assertNull(s2.outro)
    }

    @Test fun httpErrorIsProviderException() = runTest {
        try { provider().details(com.borovas.animadroid.core.model.Anime("1", "anilibria", "x")); } catch (_: ProviderException) { fail() }
        try { provider().episodes(com.borovas.animadroid.core.model.Anime("999", "anilibria", "x"), TranslationMode.SUB); fail() } catch (_: ProviderException) {}
    }

    @Test fun baseUrlIsReadPerRequest() = runTest {
        var base = "http://127.0.0.1:1"
        val p = provider(base = { base })
        try { p.search("naruto", TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        base = origin()
        assertTrue(p.search("naruto", TranslationMode.SUB).isNotEmpty())
    }

    @Test fun mirrorIsTriedOnConnectionFailure() = runTest {
        val p = provider(mirrors = listOf("http://127.0.0.1:1", origin()))
        assertTrue(p.search("naruto", TranslationMode.SUB).isNotEmpty())
    }
}
