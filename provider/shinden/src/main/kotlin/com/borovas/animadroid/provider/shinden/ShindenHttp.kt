package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.core.network.CloudflareBlockedException
import com.borovas.animadroid.core.network.CloudflareDetector
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.ProviderException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request

/** HTTP error that keeps the status code so callers can fall back (e.g. /series vs /titles). */
internal class HttpStatusException(val code: Int, val url: String) : Exception("Request failed: HTTP $code from $url")

/** Thin OkHttp wrapper shared by the provider and the host extractors. Cloudflare walls surface as [CloudflareChallengeException]. */
internal class ShindenHttp(private val client: OkHttpClient) {
    suspend fun get(url: String, referer: String? = null, userAgent: String = ANI_CLI_USER_AGENT): String =
        try {
            getChecked(url, referer, userAgent)
        } catch (e: HttpStatusException) {
            throw ProviderException(e.message ?: "HTTP ${e.code}", e)
        }

    /** Like [get] but a non-2xx status surfaces as [HttpStatusException] so callers can branch on the code. */
    suspend fun getChecked(url: String, referer: String? = null, userAgent: String = ANI_CLI_USER_AGENT): String =
        execute(client, url, referer, userAgent) { resp, text ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
            text
        }

    /** POSTs [jsonBody] and returns the response text. */
    suspend fun postJson(url: String, jsonBody: String, referer: String, origin: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", ANI_CLI_USER_AGENT).header("Referer", referer).header("Origin", origin)
            .post(jsonBody.toRequestBody("application/json".toMediaType())).build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (CloudflareDetector.isChallenge(resp.code, resp.headers) { text }) throw CloudflareChallengeException(url)
                if (!resp.isSuccessful) throw ProviderException("Request failed: HTTP ${resp.code} from $url")
                text
            }
        } catch (e: CloudflareBlockedException) {
            throw CloudflareChallengeException(e.url)
        } catch (e: IOException) {
            throw ProviderException("Connection error: could not fetch $url (${e.message})", e)
        }
    }

    /** Resolves a single redirect hop without following it; returns the absolute `Location` or null when not a redirect. */
    suspend fun redirectTarget(url: String, referer: String? = null): String? {
        val noFollow = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return execute(noFollow, url, referer, ANI_CLI_USER_AGENT) { resp, _ ->
            resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() }
        }
    }

    private suspend fun <T> execute(
        c: OkHttpClient, url: String, referer: String?, userAgent: String, handle: (okhttp3.Response, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        try {
            c.newCall(req).execute().use { resp ->
                val isRedirect = resp.code in 300..399
                val text = if (isRedirect) "" else resp.body.string()
                if (CloudflareDetector.isChallenge(resp.code, resp.headers) { text }) throw CloudflareChallengeException(url)
                handle(resp, text)
            }
        } catch (e: CloudflareBlockedException) {
            throw CloudflareChallengeException(e.url)
        } catch (e: IOException) {
            throw ProviderException("Connection error: could not fetch $url (${e.message})", e)
        }
    }
}
