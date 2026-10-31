package dev.borovas.animadroid.provider.hianime

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.core.model.TranslationMode
import dev.borovas.animadroid.core.network.CloudflareInterceptor
import dev.borovas.animadroid.provider.api.CloudflareChallengeException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HianimeProviderTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var cloudflare = false

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private fun provider(): HianimeProvider = HianimeProvider(
        OkHttpClient.Builder().addInterceptor(CloudflareInterceptor()).build(),
        server.url("/").toString().trimEnd('/'),
    )

    @Before fun setUp() {
        server = MockWebServer()
        val origin = { server.url("/").toString().trimEnd('/') }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (cloudflare) {
                    return MockResponse.Builder().code(403).body("<title>Just a moment...</title>").build()
                }
                fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
                return when {
                    path == "/search" -> ok(res("search.html"))
                    path.startsWith("/api/theme/episode/list/") -> ok(res("episodes.json"))
                    path == "/api/theme/episode/servers" -> {
                        val hash = b64("${origin()}/stream/mal/21/1/sub")
                        ok("""{"status":true,"html":"<div class=\"item server-item\" data-type=\"sub\" data-server-name=\"HD-1\" data-hash=\"x\"></div><div class=\"item server-item\" data-type=\"sub\" data-server-name=\"ZokoAnime\" data-hash=\"$hash\"></div>"}""")
                    }
                    path.startsWith("/stream/") -> {
                        val cfg = """{"src":"${origin()}/p/x/master.m3u8","subtitles":[{"lang":"pl","label":"Polish","default":false,"src":"${origin()}/s/pl.vtt"},{"lang":"en","label":"English","default":true,"src":"${origin()}/s/en.vtt"}]}"""
                        val blob = Base64.getEncoder().encodeToString(HianimeParsers.xor(cfg.toByteArray()))
                        ok("<html><script>window.__P=\"$blob\";</script></html>")
                    }
                    path == "/p/x/master.m3u8" -> ok(res("master.m3u8"))
                    path == "/one-piece-1" -> ok(res("search.html").replace("film-detail", "x"))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val anime = Anime("one-piece-1", "hianime", "One Piece")

    @Test fun search() = runTest {
        val r = provider().search("one piece", TranslationMode.SUB)
        assertEquals(2, r.size)
        val q = requests.first().url
        assertEquals("one+piece", q.encodedQuery!!.removePrefix("keyword="))
    }

    @Test fun episodes() = runTest {
        val eps = provider().episodes(anime, TranslationMode.SUB)
        assertEquals(listOf("1", "2", "10", "11.5"), eps.map { it.number })
        assertEquals("/api/theme/episode/list/1", requests.first().url.encodedPath)
    }

    @Test fun streamsFullPipeline() = runTest {
        val s = provider().streams(anime, Episode("1", "1"), TranslationMode.SUB)
        val origin = server.url("/").toString()
        assertEquals("${origin}p/x/master.m3u8", s.masterUrl)
        assertEquals(listOf(1080, 720, 360), s.variants.map { it.quality })
        assertEquals("${origin}p/x/1080/index.m3u8", s.variants[0].url)
        assertEquals("${origin}p/x/audio/ja/index.m3u8", s.externalAudioUrl)
        assertEquals(origin, s.headers["Referer"])
        assertTrue(s.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertEquals(21, s.malId)
        assertEquals(listOf("en", "pl"), s.subtitles.map { it.language })
        assertTrue(s.subtitles[0].isDefault)
        // embed request carried the site as referer; master carried the embed origin
        val embedReq = requests.first { it.url.encodedPath.startsWith("/stream/") }
        assertEquals(origin, embedReq.headers["Referer"])
        val masterReq = requests.first { it.url.encodedPath == "/p/x/master.m3u8" }
        assertEquals(origin, masterReq.headers["Referer"])
    }

    @Test fun dubMissingGivesProviderError() = runTest {
        try {
            provider().streams(anime, Episode("1", "1"), TranslationMode.DUB)
            fail()
        } catch (e: dev.borovas.animadroid.provider.api.ProviderException) {
            assertTrue(e.message!!.contains("dub"))
        }
    }

    @Test fun cloudflareMapsToChallengeException() = runTest {
        cloudflare = true
        try {
            provider().search("x", TranslationMode.SUB)
            fail()
        } catch (e: CloudflareChallengeException) {
            assertTrue(e.url.contains("/search"))
        }
    }

    @Test fun detailsNeverThrows() = runTest {
        cloudflare = true
        assertEquals(anime, provider().details(anime))
    }
}
