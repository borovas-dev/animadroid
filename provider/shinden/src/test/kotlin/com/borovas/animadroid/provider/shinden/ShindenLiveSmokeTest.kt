package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.ProviderException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real site; runs only with SHINDEN_LIVE=1 in the environment (never in CI); SHINDEN_Q and SHINDEN_MODE=DUB|SUB tune it. */
class ShindenLiveSmokeTest {
    /** Minimal per-host cookie store as a network interceptor (OkHttp's CookieJar needs the public-suffix list, absent in JVM unit tests). */
    private val cookies = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val cookieInterceptor = Interceptor { chain ->
        val host = chain.request().url.host
        val jar = cookies.getOrPut(host) { ConcurrentHashMap() }
        val req = if (jar.isEmpty()) chain.request() else chain.request().newBuilder()
            .header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" }).build()
        val resp = chain.proceed(req)
        resp.headers("Set-Cookie").forEach { h ->
            val pair = h.substringBefore(';'); jar[pair.substringBefore('=')] = pair.substringAfter('=')
        }
        resp
    }

    @Test fun searchEpisodesStreams() = runBlocking {
        assumeTrue(System.getenv("SHINDEN_LIVE") == "1")
        val mode = if (System.getenv("SHINDEN_MODE") == "DUB") TranslationMode.DUB else TranslationMode.SUB
        val p = ShindenProvider(OkHttpClient.Builder().addNetworkInterceptor(cookieInterceptor).build())
        val anime = p.search(System.getenv("SHINDEN_Q") ?: "frieren", mode).first()
        println("LIVE anime=$anime")
        val detailed = p.details(anime)
        println("LIVE details cover=${detailed.coverUrl} mal=${detailed.malId} synopsis=${detailed.synopsis?.take(60)}")
        val eps = p.episodes(anime, mode)
        println("LIVE episodes=${eps.size} first=${eps.first()} last=${eps.last()}")
        assertTrue(eps.isNotEmpty())
        try {
            println("LIVE streams=" + p.streams(anime, eps.first(), mode))
        } catch (e: ProviderException) {
            println("LIVE streams error: ${e.message}")
        }
    }
}
