package com.borovas.animadroid.provider.kickassanime

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class KickAssAnimeProviderTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var masterBody: (String) -> String = { it }
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun srv() = server.url("/").toString().trimEnd('/')
    private fun provider(base: () -> String = { srv() }) =
        KickAssAnimeProvider(OkHttpClient()) { ProviderRemoteConfig(baseUrls = listOf(base())) }
    private val show = Anime("sousou-no-frieren-2d15", "kickassanime", "Frieren")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                val q = request.url.query.orEmpty()
                fun ok(body: String) = MockResponse.Builder().code(200).body(body.replace("{{SRV}}", srv())).build()
                return when {
                    path == "/api/fsearch" -> ok(res("search.json"))
                    path.endsWith("/episodes") && "lang=en-US" in q -> ok(res(if ("page=2" in q) "episodes_en_p2.json" else "episodes_en_p1.json"))
                    path.endsWith("/episodes") && "lang=ja-JP" in q -> ok(res("episodes_ja.json"))
                    path.endsWith("/episode/ep-1-aa83b7") -> ok(res("episode_en.json"))
                    path.endsWith("/episode/ep-1-f897b3") -> ok(res("episode_ja.json"))
                    path == "/cat-player/player" -> ok(res("player.html"))
                    path.endsWith("/master.m3u8") ->
                        if (request.headers["Origin"] == srv()) ok(masterBody(res("master.m3u8")))
                        else MockResponse.Builder().code(403).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun metadata() {
        val p = provider()
        assertEquals("kickassanime", p.id); assertEquals("KickAssAnime", p.displayName)
        assertEquals(setOf("en"), p.contentLanguages); assertEquals(1, p.priority); assertEquals(false, p.gfwBlocked)
    }

    @Test fun searchMapsFieldsAndPostsJson() = runTest {
        val r = provider().search("frieren", TranslationMode.SUB)
        val a = r.first()
        assertEquals("sousou-no-frieren-2d15", a.id); assertEquals("kickassanime", a.providerId)
        assertEquals("Frieren: Beyond Journey's End", a.title); assertEquals(2023, a.year)
        assertEquals("${srv()}/image/poster/sousou-no-frieren-f6d4-hq.webp", a.coverUrl)
        val req = requests.first { it.url.encodedPath == "/api/fsearch" }
        assertEquals("POST", req.method); assertEquals("""{"query":"frieren"}""", req.body!!.utf8())
    }

    @Test fun dubSearchKeepsOnlyShowsWithEnglishDub() = runTest {
        val sub = provider().search("frieren", TranslationMode.SUB)
        val dub = provider().search("frieren", TranslationMode.DUB)
        assertTrue(dub.isNotEmpty() && dub.size < sub.size)
        assertTrue(dub.all { it.id != "sousou-no-frieren-no-mahou-561b" })
    }

    @Test fun baseUrlIsReadPerRequest() = runTest {
        var base = "http://127.0.0.1:1"
        val p = provider { base }
        try { p.search("x", TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        base = srv()
        assertTrue(p.search("x", TranslationMode.SUB).isNotEmpty())
    }

    @Test fun episodesPaginateAndSort() = runTest {
        val eps = provider().episodes(show, TranslationMode.DUB)
        assertEquals(listOf("1", "2", "3"), eps.map { it.number })
        assertEquals("ep-1-aa83b7", eps[0].id); assertEquals("The Journey's End", eps[0].title)
        assertEquals(2, requests.count { it.url.encodedPath.endsWith("/episodes") })
        val sub = provider().episodes(show, TranslationMode.SUB)
        assertEquals("ep-1-f897b3", sub.single().id)
    }

    @Test fun dubStreamsSelectEnglishAudioAndSendOrigin() = runTest {
        val p = provider()
        val s = p.streams(show, p.episodes(show, TranslationMode.DUB).first(), TranslationMode.DUB)
        assertEquals(listOf(1080, 720, 360), s.variants.map { it.quality })
        assertEquals("${srv()}/manifest/67d0c079169c31976b8d7970/67d0c149a60e80857fb0ed26/playlist.m3u8", s.externalAudioUrl)
        assertNull(s.masterUrl)
        assertEquals(srv(), s.headers["Origin"]); assertEquals("${srv()}/", s.headers["Referer"]); assertTrue(s.headers["User-Agent"]!!.contains("Chrome"))
        assertEquals("English dub", s.languageLabel)
        val en = s.subtitles.first()
        assertEquals("en", en.language); assertTrue(en.isDefault); assertEquals(1, s.subtitles.count { it.isDefault })
        assertEquals("${srv()}/subs/67d0c079169c31976b8d7970/67e0fa641967208e6c20882f.vtt", en.url)
        assertTrue(s.subtitles.size >= 8)
    }

    @Test fun subStreamsUseJapaneseAudio() = runTest {
        val p = provider()
        val s = p.streams(show, Episode("ep-1-f897b3", "1"), TranslationMode.SUB)
        assertTrue(s.externalAudioUrl!!.endsWith("/67d0c149a60e80857fb0ed28/playlist.m3u8"))
        assertEquals("Japanese audio + subs", s.languageLabel)
    }

    @Test fun missingDubIsProviderException() = runTest {
        val p = provider()
        // a ja-JP episode requested as DUB
        try { p.streams(show, Episode("ep-1-f897b3", "1"), TranslationMode.DUB); fail() } catch (_: ProviderException) {}
        // master without an English rendition
        masterBody = { m -> m.lines().filterNot { it.contains("LANGUAGE=\"eng\"") }.joinToString("\n") }
        try { p.streams(show, Episode("ep-1-aa83b7", "1"), TranslationMode.DUB); fail() } catch (e: ProviderException) {
            assertTrue(e.message!!.contains("English dub"))
        }
    }

    @Test fun badIdsAreRejected() = runTest {
        try { provider().streams(show, Episode("../x", "1"), TranslationMode.SUB); fail() } catch (_: ProviderException) {}
        try { provider().episodes(Anime("a/b", "kickassanime", "x"), TranslationMode.SUB); fail() } catch (_: ProviderException) {}
    }
}
