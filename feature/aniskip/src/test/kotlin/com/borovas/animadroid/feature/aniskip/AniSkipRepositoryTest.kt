package com.borovas.animadroid.feature.aniskip

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSkipRepositoryTest {
    private fun server(vararg bodies: Pair<Int, String>) = MockWebServer().apply {
        bodies.forEach { (c, b) -> enqueue(MockResponse.Builder().code(c).body(b).build()) }
        start()
    }

    private fun base(s: MockWebServer) = s.url("/").toString().trimEnd('/')

    private val ok = """{"found":true,"results":[
        {"interval":{"start_time":5.5,"end_time":95.0},"skip_type":"op","episode_length":1400},
        {"interval":{"start_time":1300.0,"end_time":1390.0},"skip_type":"ed"}]}"""

    @Test fun parsesAndCaches() = runTest {
        val s = server(200 to ok)
        val r = AniSkipRepository(OkHttpClient(), base(s))
        val t = r.skipTimes(1, 2)!!
        assertEquals(5.5, t.opening!!.startSec, 0.0)
        assertEquals(1390.0, t.ending!!.endSec, 0.0)
        val req = s.takeRequest()
        assertTrue(req.url.toString().contains("/v1/skip-times/1/2"))
        assertEquals(listOf("op", "ed"), req.url.queryParameterValues("types"))
        assertNotNull(r.skipTimes(1, 2))
        assertEquals(1, s.requestCount)
        s.close()
    }

    @Test fun edBeforeOpEndDropped() = runTest {
        val body = """{"found":true,"results":[
            {"interval":{"start_time":10,"end_time":100},"skip_type":"op"},
            {"interval":{"start_time":50,"end_time":120},"skip_type":"ed"}]}"""
        val s = server(200 to body)
        val t = AniSkipRepository(OkHttpClient(), base(s)).skipTimes(1, 1)!!
        assertNull(t.ending)
        s.close()
    }

    @Test fun invalidOpDropped() {
        assertNull(buildSkipTimes(listOf(SkipResult(Interval(50.0, 40.0), "op"))))
    }

    @Test fun failuresReturnNull() = runTest {
        val s = server(404 to "{}", 500 to "x", 200 to "garbage", 200 to """{"found":false}""")
        val r = AniSkipRepository(OkHttpClient(), base(s))
        assertNull(r.skipTimes(1, 1)); assertNull(r.skipTimes(2, 1))
        assertNull(r.skipTimes(3, 1)); assertNull(r.skipTimes(4, 1))
        s.close()
    }

    @Test fun malResolverPicksExact() = runTest {
        val body = """{"categories":[{"type":"anime","items":[
            {"id":1,"name":"Naruto Shippuden"},{"id":2,"name":"Naruto"}]}]}"""
        val s = server(200 to body)
        val id = MalIdResolver(OkHttpClient(), base(s)).resolve("Naruto!")
        assertEquals(2, id)
        assertEquals("Naruto", s.takeRequest().url.queryParameter("keyword"))
        s.close()
    }

    @Test fun malResolverContainsAndFailure() = runTest {
        val s = server(
            200 to """{"categories":[{"type":"anime","items":[{"id":7,"name":"Foo"},{"id":8,"name":"Bar Baz Season 2"}]}]}""",
            500 to "",
        )
        val r = MalIdResolver(OkHttpClient(), base(s))
        assertEquals(8, r.resolve("Bar Baz"))
        assertNull(r.resolve("x"))
        s.close()
    }
}
