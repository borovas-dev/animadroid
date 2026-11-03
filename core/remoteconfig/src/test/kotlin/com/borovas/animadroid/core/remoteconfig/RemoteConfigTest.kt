package com.borovas.animadroid.core.remoteconfig

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteConfigTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = kp.public.encoded.takeLast(32).toByteArray()

    private fun sign(body: String): String {
        val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(body.encodeToByteArray())
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun doc(rev: Int, url: String = "https://x.example", min: String? = null) =
        """{"revision":$rev,"providers":{"p":{"baseUrls":["$url/"],"minAppVersion":${min?.let { "\"$it\"" } ?: "null"},"params":{"k":"v"}}}}"""

    private val bundled = doc(1, "https://bundled.example")

    @Before fun setUp() { server = MockWebServer().also { it.start() }; dir = kotlin.io.path.createTempDirectory().toFile() }
    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun repo(allowUnsigned: Boolean = false, key: ByteArray? = pub, version: String = "1.0.0") = RemoteConfigRepository(
        OkHttpClient(), dir, { bundled }, key, allowUnsigned, version, server.url("/providers.json").toString(),
    )

    @Test fun rfc8032Vector1() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pk = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519.verify(pk, ByteArray(0), sig))
        assertFalse(Ed25519.verify(pk, byteArrayOf(1), sig))
    }

    @Test fun usesBundledWhenNothingFetched() {
        val r = repo()
        assertEquals("https://bundled.example", r.forProvider("p").baseUrl)
        assertEquals(ProviderRemoteConfig.DEFAULT, r.forProvider("unknown"))
        assertTrue(r.forProvider("unknown").enabled)
    }

    @Test fun validSignatureIsAdoptedAndCached() = runBlocking {
        val body = doc(2)
        server.enqueue(MockResponse.Builder().code(200).body(body).addHeader("ETag", "\"abc\"").build())
        server.enqueue(MockResponse.Builder().code(200).body(sign(body)).build())
        val r = repo()
        assertTrue(r.refresh())
        assertEquals("https://x.example", r.forProvider("p").baseUrl)
        assertEquals("v", r.forProvider("p").param("k"))
        // A fresh instance reads the verified cache.
        assertEquals("https://x.example", repo().forProvider("p").baseUrl)
    }

    @Test fun badSignatureFallsBack() = runBlocking {
        val body = doc(2)
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        server.enqueue(MockResponse.Builder().code(200).body(sign(doc(3))).build())
        val r = repo()
        assertFalse(r.refresh())
        assertEquals("https://bundled.example", r.forProvider("p").baseUrl)
        assertFalse(File(dir, "providers.json").exists())
    }

    @Test fun missingSignatureRejectedInReleaseAcceptedInDebug() = runBlocking {
        val body = doc(2)
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            server.enqueue(MockResponse.Builder().code(404).build())
        }
        assertFalse(repo(allowUnsigned = false).refresh())
        assertTrue(repo(allowUnsigned = true).refresh())
    }

    @Test fun placeholderKeyRejectsEverything() = runBlocking {
        val body = doc(2)
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        server.enqueue(MockResponse.Builder().code(200).body(sign(body)).build())
        assertFalse(repo(key = RemoteConfigKeys.publicKey()).refresh())
    }

    @Test fun tamperedCacheIsIgnored() {
        File(dir, "providers.json").writeText(doc(9, "https://evil.example"))
        File(dir, "providers.json.sig").writeText(sign(doc(9, "https://other.example")))
        assertEquals("https://bundled.example", repo().forProvider("p").baseUrl)
    }

    @Test fun etagSentAndNotModifiedKeepsCache() = runBlocking {
        val body = doc(2)
        server.enqueue(MockResponse.Builder().code(200).body(body).addHeader("ETag", "\"abc\"").build())
        server.enqueue(MockResponse.Builder().code(200).body(sign(body)).build())
        val r = repo()
        assertTrue(r.refresh())
        server.takeRequest(); server.takeRequest()
        server.enqueue(MockResponse.Builder().code(304).build())
        assertFalse(r.refresh())
        assertEquals("\"abc\"", server.takeRequest().headers["If-None-Match"])
        assertEquals("https://x.example", r.forProvider("p").baseUrl)
    }

    @Test fun rollbackToOlderRevisionIgnored() = runBlocking {
        val body = doc(0, "https://old.example")
        server.enqueue(MockResponse.Builder().code(200).body(body).build())
        server.enqueue(MockResponse.Builder().code(200).body(sign(body)).build())
        assertFalse(repo().refresh())
    }

    @Test fun networkErrorKeepsBundled() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val r = repo()
        assertFalse(r.refresh())
        assertEquals("https://bundled.example", r.forProvider("p").baseUrl)
    }

    @Test fun minAppVersionDisablesProvider() {
        val b = doc(1, min = "2.0.0")
        val r = RemoteConfigRepository(OkHttpClient(), dir, { b }, pub, false, "1.9.9", "http://localhost/x")
        assertFalse(r.forProvider("p").enabled)
        val r2 = RemoteConfigRepository(OkHttpClient(), dir, { b }, pub, false, "2.0.0", "http://localhost/x")
        assertTrue(r2.forProvider("p").enabled)
    }

    @Test fun bundledResourceMatchesConfigDir() {
        val repoCopy = File("../../config/providers.json")
        val res = File("src/main/resources/providers.json")
        assertEquals(repoCopy.readText(), res.readText())
        val cfg = kotlinx.serialization.json.Json.decodeFromString<RemoteConfig>(res.readText())
        assertEquals(
            setOf("hianime", "kickassanime", "animeparadise", "shinden", "anilibria", "animefire"), cfg.providers.keys,
        )
    }
}
