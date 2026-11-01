package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.Anime
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Provider flow against a mock server serving the saved fixtures; offline. Host pages are synthetic (see HostExtractorsTest). */
class ShindenProviderTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<String>()
    private var episodePlayers = "episode_players_external_only.html"
    private var showBody = res("player_show_external.html")

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun origin() = server.url("/").toString().trimEnd('/')
    private fun provider() = ShindenProvider(OkHttpClient(), origin(), origin(), antibotDelayMs = 0)
    private val anime = Anime("61064-sousou-no-frieren", "shinden", "Frieren")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                seen += path + (request.url.encodedQuery?.let { "?$it" } ?: "")
                val body = when {
                    path == "/series" -> res("search.html")
                    path == "/series/61064-sousou-no-frieren" -> res("series.html")
                    path == "/series/61064-sousou-no-frieren/episodes" && request.url.queryParameter("strona") == null -> res("episodes.html")
                    path == "/series/61064-sousou-no-frieren/episodes" -> res("episodes_langs.html")
                    path.startsWith("/titles/2395-") -> "<html></html>"
                    path.startsWith("/episode/") -> res(episodePlayers)
                    path.endsWith("/player_load") -> "5"
                    path.endsWith("/player_show") -> showBody
                    path == "/e/T1R2ZAa8nyc08" -> "<html></html>"
                    path == "/api/stream" -> res("vidara_stream.json")
                    path == "/embed/1" -> """player.src([{src: "/v/h/1.mp4", type: "video/mp4"}])"""
                    path == "/v/h/1.mp4" -> return MockResponse.Builder().code(302).addHeader("Location", "${origin()}/cdn/final.mp4").build()
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun searchAndDetails() = runTest {
        val p = provider()
        val r = p.search("frieren", TranslationMode.SUB)
        assertEquals(4, r.size)
        assertTrue(seen.single().startsWith("/series?search=frieren"))
        val d = p.details(r[0])
        assertTrue(d.synopsis!!.startsWith("Król"))
    }

    @Test fun episodesFollowPaginationAndAscend() = runTest {
        val eps = provider().episodes(anime, TranslationMode.SUB)
        // page 1 fixture has 28..23 (6 rows), page 2 fixture has 3 more; reversed to ascending overall
        assertEquals(9, eps.size)
        assertEquals("28", eps.last().number)
        assertTrue(seen.any { it.contains("strona=2") })
    }

    @Test fun titlesPrefixFallbackOn404() = runTest {
        val p = provider()
        val e = runCatching { p.episodes(Anime("2395-death-note", "shinden", "DN"), TranslationMode.SUB) }.exceptionOrNull()
        assertTrue(e is ProviderException) // /titles page is empty -> no episodes, but the 404 on /series was handled
        assertTrue(seen.first().startsWith("/series/2395-death-note"))
        assertTrue(seen.any { it.startsWith("/titles/2395-death-note") })
    }

    @Test fun guestOnlyExternalLinksGiveClearError() = runTest {
        try {
            provider().streams(anime, Episode("226988", "1"), TranslationMode.SUB)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!, e.message!!.contains("Crunchyroll"))
            assertTrue(e.message!!.contains("external service"))
        }
        assertTrue(seen.none { it.endsWith("player_show") }) // link-out services are skipped without API calls
    }

    @Test fun dubModeWithoutPolishAudioExplains() = runTest {
        try {
            provider().streams(anime, Episode("226988", "1"), TranslationMode.DUB)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.message!!, e.message!!.startsWith("No Polish dub/lektor players"))
        }
    }

    @Test fun realPlayerListPicksVidaraHlsForPolishSubs() = runTest {
        episodePlayers = "episode_players.html"
        showBody = """<iframe src="${origin()}/e/T1R2ZAa8nyc08"></iframe>"""
        val set = provider().streams(anime, Episode("226988", "1"), TranslationMode.SUB)
        assertTrue(set.masterUrl!!.contains("master.m3u8"))
        assertEquals("JP audio + PL subs", set.languageLabel)
        assertEquals("${origin()}/e/T1R2ZAa8nyc08", set.headers["Referer"])
        assertEquals(origin(), set.headers["Origin"])
        // exactly one player_load/show pair: the best native host (Vidara, 1080p) won first try
        assertEquals(1, seen.count { it.endsWith("player_show?auth=X2d1ZXN0XzowLDUsMjEwMDAwMDAsMjU1LDQxNzQyOTM2NDQ%3D&width=0&height=-1") })
        assertTrue(seen.any { it.startsWith("/xhr/1879637/player_load") })
    }

    @Test fun embeddedHostResolvesToStreamWithHeadersAndLabel() = runTest {
        episodePlayers = "episode_players_sibnet.html"
        showBody = """<iframe src="${origin()}/embed/1"></iframe>"""
        val p = provider().also { it.hostResolver = { SibnetExtractor } } // mock host stands in for video.sibnet.ru
        val set = p.streams(anime, Episode("1", "1"), TranslationMode.DUB)
        assertEquals("${origin()}/cdn/final.mp4", set.variants.single().url)
        assertEquals("Polish dub/lektor", set.languageLabel)
        assertEquals("https://video.sibnet.ru/", set.headers["Referer"])
        assertTrue(seen.any { it.contains("/xhr/9001/player_load") && it.contains("auth=X2d1ZXN0XzowLDUs") })
    }
}
