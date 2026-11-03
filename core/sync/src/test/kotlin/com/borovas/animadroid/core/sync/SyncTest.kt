package com.borovas.animadroid.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val tokens = InMemoryTokenStore()
    private val queue = InMemoryQueueStore()
    private var retries = 0
    private var now = 1_000_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.close() }

    private fun repo(config: SyncConfig = SyncConfig("al", "mal")) = SyncRepositoryImpl(
        config,
        AniListClient(http, server.url("/graphql").toString()),
        MalClient(http, server.url("/v2").toString(), server.url("/oauth").toString()),
        tokens, queue, { retries++ }, CoroutineScope(Dispatchers.Unconfined), { now },
    )

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    @Test fun pkceVerifierAndChallenge() {
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it in "-._~" })
        assertEquals(v, Pkce.challenge(v)) // plain method
        assertTrue(Pkce.verifier() != v)
    }

    @Test fun notConfiguredHasNoAuthUrl() = runTest {
        val r = repo(SyncConfig())
        assertNull(r.authorizeUrl(SyncService.MAL))
        assertFalse(r.state.value.services.getValue(SyncService.MAL).configured)
    }

    @Test fun malAuthUrlUsesPlainPkce() {
        val url = repo().authorizeUrl(SyncService.MAL)!!
        assertTrue(url.contains("code_challenge_method=plain"))
        assertTrue(url.contains("redirect_uri=animadroid%3A%2F%2Fauth%2Fmal"))
        assertTrue(url.contains("client_id=mal"))
    }

    @Test fun malRedirectExchangesCodeWithVerifier() = runTest {
        val r = repo()
        val url = r.authorizeUrl(SyncService.MAL)!!
        val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        val challenge = Regex("code_challenge=([^&]+)").find(url)!!.groupValues[1]
        server.enqueue(json("""{"access_token":"A","refresh_token":"R","expires_in":3600}"""))
        server.enqueue(json("""{"name":"miryam"}"""))
        assertTrue(r.handleRedirect("animadroid://auth/mal?code=CODE&state=$state"))
        val req = server.takeRequest()
        val body = req.body!!.utf8()
        assertTrue(body, "code=CODE" in body && "code_verifier=$challenge" in body && "grant_type=authorization_code" in body)
        assertEquals("miryam", tokens.get(SyncService.MAL)?.username)
        assertEquals("Bearer A", server.takeRequest().headers["Authorization"])
    }

    @Test fun malRedirectRejectsWrongState() = runTest {
        val r = repo()
        r.authorizeUrl(SyncService.MAL)
        assertTrue(r.handleRedirect("animadroid://auth/mal?code=C&state=bad"))
        assertNull(tokens.get(SyncService.MAL))
        assertEquals(0, server.requestCount)
    }

    @Test fun anilistImplicitRedirectStoresToken() = runTest {
        val r = repo()
        server.enqueue(json("""{"data":{"Viewer":{"name":"bob"}}}"""))
        assertTrue(r.handleRedirect("animadroid://auth/anilist#access_token=TOK&token_type=Bearer&expires_in=3600"))
        assertEquals("TOK", tokens.get(SyncService.ANILIST)?.access)
        assertEquals("bob", r.state.value.services.getValue(SyncService.ANILIST).username)
    }

    @Test fun anilistScrobbleSendsLookupThenCompletedMutation() = runTest {
        tokens.put(SyncService.ANILIST, Tokens("TOK"))
        val r = repo()
        server.enqueue(json("""{"data":{"Media":{"id":99,"episodes":12,"mediaListEntry":{"progress":10,"status":"CURRENT"}}}}"""))
        server.enqueue(json("""{"data":{"SaveMediaListEntry":{"id":1,"progress":12,"status":"COMPLETED"}}}"""))
        r.record(malId = 5, episode = 12, total = null)
        r.syncNow()
        val lookup = server.takeRequest()
        assertEquals("Bearer TOK", lookup.headers["Authorization"])
        assertTrue(lookup.body!!.utf8().contains("\"idMal\":5"))
        val save = server.takeRequest().body!!.utf8()
        assertTrue(save, "SaveMediaListEntry" in save && "\"mediaId\":99" in save && "\"progress\":12" in save && "\"status\":\"COMPLETED\"" in save)
        assertEquals(0, r.state.value.pending)
    }

    @Test fun doesNotRegressRemoteProgress() = runTest {
        tokens.put(SyncService.ANILIST, Tokens("TOK"))
        val r = repo()
        server.enqueue(json("""{"data":{"Media":{"id":99,"episodes":12,"mediaListEntry":{"progress":8,"status":"CURRENT"}}}}"""))
        r.record(5, 3, null)
        r.syncNow()
        assertEquals(1, server.requestCount)
        assertEquals(0, queue.load().size)
    }

    @Test fun malScrobbleSendsPatchForm() = runTest {
        tokens.put(SyncService.MAL, Tokens("A", "R", now + 3_600_000))
        val r = repo()
        server.enqueue(json("""{"num_episodes":24,"my_list_status":{"num_episodes_watched":2}}"""))
        server.enqueue(json("""{"status":"watching","num_episodes_watched":3}"""))
        r.record(7, 3, null)
        r.syncNow()
        val get = server.takeRequest()
        assertEquals("GET", get.method); assertTrue(get.target!!.startsWith("/v2/anime/7?"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v2/anime/7/my_list_status", patch.target)
        assertEquals("Bearer A", patch.headers["Authorization"])
        val body = patch.body!!.utf8()
        assertTrue(body, "status=watching" in body && "num_watched_episodes=3" in body)
    }

    @Test fun failedUpdateStaysQueuedAndRetriesIdempotently() = runTest {
        tokens.put(SyncService.ANILIST, Tokens("TOK"))
        val r = repo()
        r.record(5, 4, null)
        r.record(5, 3, null) // coalesces to the furthest episode
        assertEquals(listOf(4), queue.load().map { it.episode })
        server.enqueue(json("down", 503))
        r.syncNow()
        assertEquals(1, queue.load().size)
        assertEquals(1, retries)
        server.enqueue(json("""{"data":{"Media":{"id":1,"episodes":null,"mediaListEntry":null}}}"""))
        server.enqueue(json("""{"data":{"SaveMediaListEntry":{"id":1}}}"""))
        r.syncNow()
        assertEquals(0, queue.load().size)
        assertTrue(server.takeRequest().body!!.utf8().isNotEmpty())
    }

    @Test fun malRefreshesExpiredTokenAndPersistsIt() = runTest {
        tokens.put(SyncService.MAL, Tokens("OLD", "R1", now - 1, "u"))
        val r = repo()
        server.enqueue(json("""{"access_token":"NEW","refresh_token":"R2","expires_in":3600}"""))
        server.enqueue(json("""{"num_episodes":12,"my_list_status":null}"""))
        server.enqueue(json("{}"))
        r.record(7, 1, null)
        r.syncNow()
        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.target)
        val b = refresh.body!!.utf8()
        assertTrue(b, "grant_type=refresh_token" in b && "refresh_token=R1" in b && "client_id=mal" in b)
        assertEquals("Bearer NEW", server.takeRequest().headers["Authorization"])
        val t = tokens.get(SyncService.MAL)!!
        assertEquals("NEW", t.access); assertEquals("R2", t.refresh); assertEquals("u", t.username)
    }

    @Test fun malRetriesOnceAfter401() = runTest {
        tokens.put(SyncService.MAL, Tokens("STALE", "R1", now + 3_600_000))
        val r = repo()
        server.enqueue(json("{}", 401))
        server.enqueue(json("""{"access_token":"NEW","expires_in":3600}"""))
        server.enqueue(json("""{"num_episodes":12}"""))
        server.enqueue(json("{}"))
        r.record(7, 1, null)
        r.syncNow()
        assertEquals(4, server.requestCount)
        assertEquals(0, queue.load().size)
        assertEquals("R1", tokens.get(SyncService.MAL)?.refresh) // kept when the server sends none
    }

    @Test fun revokedRefreshTokenDisconnects() = runTest {
        tokens.put(SyncService.MAL, Tokens("OLD", "R1", now - 1))
        val r = repo()
        server.enqueue(json("""{"error":"invalid_grant"}""", 400))
        r.record(7, 1, null)
        r.syncNow()
        assertNull(tokens.get(SyncService.MAL))
        assertEquals(0, queue.load().size)
        assertNotNull(r.state.value.error)
    }

    @Test fun onEpisodeWatchedIgnoresShowsWithoutMalIdAndFractionalEpisodes() = runTest {
        tokens.put(SyncService.ANILIST, Tokens("TOK"))
        val r = repo()
        val a = com.borovas.animadroid.core.model.Anime("x", "p", "t")
        r.onEpisodeWatched(a, "3", null)
        r.onEpisodeWatched(a.copy(malId = 1), "12.5", null)
        assertEquals(0, server.requestCount)
        assertEquals(0, queue.load().size)
    }
}
