package com.borovas.animadroid.core.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Non-2xx answer from a tracker. Network failures stay [IOException]. */
class SyncHttpException(val code: Int, message: String) : Exception("HTTP $code: $message") {
    val unauthorized get() = code == 401
    val notFound get() = code == 404
    /** Worth retrying later (rate limit / server trouble). */
    val transient get() = code == 429 || code >= 500
}

internal suspend fun OkHttpClient.call(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { r ->
        val body = r.body.string()
        if (!r.isSuccessful) throw SyncHttpException(r.code, body.take(200))
        body
    }
}
