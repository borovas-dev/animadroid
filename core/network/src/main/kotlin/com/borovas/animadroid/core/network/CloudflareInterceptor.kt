package com.borovas.animadroid.core.network

import java.io.IOException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/** Thrown when Cloudflare served a challenge page instead of the real content. */
class CloudflareBlockedException(val url: String) : IOException("Cloudflare challenge at $url")

object CloudflareDetector {
    /** 403/503 with "Just a moment" body or `cf-mitigated: challenge` header. */
    fun isChallenge(code: Int, headers: Headers, bodyPrefix: () -> String): Boolean {
        if (code != 403 && code != 503) return false
        if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return true
        return bodyPrefix().contains("Just a moment", ignoreCase = true)
    }
}

class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val blocked = CloudflareDetector.isChallenge(response.code, response.headers) {
            runCatching { response.peekBody(PEEK_BYTES).string() }.getOrDefault("")
        }
        if (blocked) {
            val url = response.request.url.toString()
            response.close()
            throw CloudflareBlockedException(url)
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 64L * 1024
    }
}
