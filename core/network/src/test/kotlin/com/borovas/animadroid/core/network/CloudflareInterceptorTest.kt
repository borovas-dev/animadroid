package dev.borovas.animadroid.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareInterceptorTest {
    private val client = OkHttpClient.Builder().addInterceptor(CloudflareInterceptor()).build()

    private fun withServer(response: MockResponse, block: (String) -> Unit) {
        MockWebServer().use { s ->
            s.enqueue(response)
            s.start()
            block(s.url("/x").toString())
        }
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test fun passesThroughOk() = withServer(MockResponse.Builder().code(200).body("hello").build()) {
        get(it).use { r -> assertEquals("hello", r.body.string()) }
    }

    @Test fun detectsJustAMoment403() = withServer(
        MockResponse.Builder().code(403).body("<title>Just a moment...</title>").build(),
    ) { url ->
        try {
            get(url)
            fail()
        } catch (e: CloudflareBlockedException) {
            assertEquals(url, e.url)
        }
    }

    @Test fun detectsMitigatedHeader503() = withServer(
        MockResponse.Builder().code(503).addHeader("cf-mitigated", "challenge").body("x").build(),
    ) { url ->
        try {
            get(url)
            fail()
        } catch (e: CloudflareBlockedException) {
            assertTrue(e.message!!.contains(url))
        }
    }

    @Test fun plain403IsNotChallenge() = withServer(MockResponse.Builder().code(403).body("forbidden").build()) {
        get(it).use { r -> assertEquals(403, r.code) }
    }

    @Test fun justAMomentOn200IsIgnoredByInterceptor() = withServer(
        MockResponse.Builder().code(200).body("Just a moment").build(),
    ) { get(it).use { r -> assertEquals(200, r.code) } }
}
