package com.borovas.animadroid.provider.common

import com.borovas.animadroid.provider.api.ProviderException
import java.io.IOException

/** True for connection failures and 5xx responses (as produced by [HostHttp]); other failures would repeat on a mirror. */
fun ProviderException.isMirrorRetryable(): Boolean {
    // Walk the chain: coroutine stack-trace recovery wraps the original exception as a cause.
    var c: Throwable? = cause
    repeat(8) {
        when {
            c is IOException -> return true
            c is HttpStatusException -> return (c as HttpStatusException).code >= 500
        }
        c = c?.cause ?: return false
    }
    return false
}

/**
 * Runs [block] against each base URL in order, moving on to the next mirror on a connection failure or HTTP 5xx.
 * The last failure is rethrown when every mirror fails; other errors surface immediately.
 */
suspend fun <T> withMirrors(bases: List<String>, block: suspend (base: String) -> T): T {
    var last: ProviderException? = null
    for (b in bases) {
        try {
            return block(b)
        } catch (e: ProviderException) {
            if (!e.isMirrorRetryable()) throw e
            last = e
        }
    }
    throw last ?: ProviderException("No base URL configured")
}
