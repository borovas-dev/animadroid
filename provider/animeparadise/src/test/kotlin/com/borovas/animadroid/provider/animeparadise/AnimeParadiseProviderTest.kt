package com.borovas.animadroid.provider.animeparadise

import com.borovas.animadroid.core.model.Anime
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

class AnimeParadiseProviderTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun origin() = server.url("/").toString().trimEnd('/')
    private fun provider(ass: Boolean = false) = AnimeParadiseProvider(
        OkHttpClient(),
        { ProviderRemoteConfig(baseUrls = listOf(origin()), params = mapOf("streamBase" to origin())) },
        ass,
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
                return when (request.url.encodedPath) {
                    "/search" -> ok(res("search.json"))
                    "/anime/naruto" -> ok(res("details.json"))
                    "/anime/OGCLrfH4Gdr4yGZg/episode" -> ok(res("episodes.json"))
                    "/ep/a9d35391-5437-4b2a-9445-7066f14b15d5" -> ok(res("episode.json"))
                    "/m3u8" -> ok(res("master.m3u8"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val anime = Anime("naruto", "animeparadise", "Naruto")

    @Test fun metadata() {
        val p = provider()
        assertEquals("animeparadise", p.id)
        assertEquals(2, p.priority)
        assertEquals(setOf("en"), p.contentLanguages)
        assertTrue(!p.gfwBlocked)
    }

    @Test fun search() = runTest {
        val r = provider().search("naruto shippuden", TranslationMode.SUB)
        assertEquals(2, r.size)
        assertEquals("naruto-shippuden", r[0].id)
        assertEquals(500, r[0].subEpisodes)
        assertEquals(2007, r[0].year)
        assertTrue(r[0].coverUrl!!.startsWith("https://s4.anilist.co"))
        val q = requests.first()
        assertEquals("POPULARITY", q.url.queryParameter("sort"))
        assertEquals("naruto shippuden", q.url.queryParameter("q"))
        assertEquals("1", q.url.queryParameter("v"))
        assertEquals("https://www.animeparadise.moe", q.headers["Origin"])
        assertEquals("https://www.animeparadise.moe/", q.headers["Referer"])
    }

    @Test fun dubIsEmpty() = runTest {
        assertTrue(provider().search("naruto", TranslationMode.DUB).isEmpty())
        try { provider().episodes(anime, TranslationMode.DUB); fail() } catch (_: ProviderException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun details() = runTest {
        val d = provider().details(anime)
        assertTrue(d.synopsis!!.startsWith("Naruto Uzumaki"))
        assertEquals(2002, d.year)
        assertEquals(20, d.malId)
    }

    @Test fun episodes() = runTest {
        val eps = provider().episodes(anime, TranslationMode.SUB)
        assertEquals(listOf("/anime/naruto", "/anime/OGCLrfH4Gdr4yGZg/episode"), requests.map { it.url.encodedPath })
        assertEquals(listOf("1", "2", "3"), eps.map { it.number })
        assertEquals("a9d35391-5437-4b2a-9445-7066f14b15d5|OGCLrfH4Gdr4yGZg", eps[0].id)
        assertEquals("Enter: Naruto Uzumaki!", eps[0].title)
    }

    @Test fun streams() = runTest {
        val ep = com.borovas.animadroid.core.model.Episode("a9d35391-5437-4b2a-9445-7066f14b15d5|OGCLrfH4Gdr4yGZg", "1")
        val s = provider().streams(anime, ep, TranslationMode.SUB)
        val epReq = requests.first { it.url.encodedPath.startsWith("/ep/") }
        assertEquals("OGCLrfH4Gdr4yGZg", epReq.url.queryParameter("origin"))
        val m3u8Req = requests.first { it.url.encodedPath == "/m3u8" }
        assertTrue(m3u8Req.url.queryParameter("url")!!.startsWith("Yi7ml55vdtSC1B9H6IvudeFcCyy3C0u_"))
        assertTrue(s.masterUrl!!.startsWith("${origin()}/m3u8?url=Yi7ml55"))
        assertEquals(1, s.variants.size)
        assertEquals(800, s.variants[0].quality)
        assertEquals("${origin()}/m3u8?url=VARIANTTOKEN", s.variants[0].url)
        assertEquals("https://www.animeparadise.moe", s.headers["Origin"])
        assertEquals("https://www.animeparadise.moe/", s.headers["Referer"])
        // intro 0..0 is "none"; outro maps to seconds
        assertNull(s.intro)
        assertEquals(1280.0, s.outro!!.startSec, 0.0)
        assertEquals(1369.0, s.outro!!.endSec, 0.0)
        assertEquals(1, s.subtitles.size)
        val t = s.subtitles[0]
        assertEquals("en", t.language); assertTrue(t.isDefault)
        assertTrue(t.url.startsWith("https://stream.animeparadise.moe/captions?url="))
    }

    @Test fun assTracksOptIn() = runTest {
        val ep = com.borovas.animadroid.core.model.Episode("a9d35391-5437-4b2a-9445-7066f14b15d5|OGCLrfH4Gdr4yGZg", "1")
        val subs = provider(ass = true).streams(anime, ep, TranslationMode.SUB).subtitles
        assertEquals(listOf("en", "en", "es", "pt", "it").sorted(), subs.map { it.language.orEmpty() }.sorted())
        assertEquals("vtt first", true, subs[0].url.contains("/captions"))
        assertEquals(1, subs.count { it.isDefault })
        assertTrue(subs.any { it.url == "${origin()}/stream/file/13KMJl5GaxxuyYzoqV-aASt7SiMGyqgqm" })
    }

    @Test fun baseUrlReadPerRequest() = runTest {
        var base = "http://127.0.0.1:1"
        val p = AnimeParadiseProvider(OkHttpClient(), config = { ProviderRemoteConfig(baseUrls = listOf(base)) })
        try { p.search("x", TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        base = origin()
        assertEquals(2, p.search("x", TranslationMode.SUB).size)
    }

    @Test fun httpErrorIsProviderException() = runTest {
        try { provider().details(anime.copy(id = "x")); } catch (_: ProviderException) { fail() }
        try { provider().episodes(anime.copy(id = "x"), TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        assertNotNull(requests)
    }
}
