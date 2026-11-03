package com.borovas.animadroid.provider.common

import com.borovas.animadroid.provider.api.ProviderException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MirrorsTest {
    @Test fun movesOnAfterConnectionFailureAnd5xx() = runTest {
        val tried = mutableListOf<String>()
        val r = withMirrors(listOf("a", "b", "c")) { b ->
            tried += b
            when (b) {
                "a" -> throw ProviderException("x", IOException())
                "b" -> throw ProviderException("x", HttpStatusException(503, "u"))
                else -> "ok"
            }
        }
        assertEquals("ok", r); assertEquals(listOf("a", "b", "c"), tried)
    }

    @Test fun clientErrorsAreNotRetried() = runTest {
        val tried = mutableListOf<String>()
        try {
            withMirrors(listOf("a", "b")) { b -> tried += b; throw ProviderException("x", HttpStatusException(404, "u")) }
            fail()
        } catch (_: ProviderException) {}
        assertEquals(listOf("a"), tried)
    }
}
