package com.borovas.animadroid.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePrefetcherTest {
    private var now = 0L
    private val p = NextEpisodePrefetcher<String>({ now })
    private fun start(pos: Long, enabled: Boolean = true, metered: Boolean = false, allow: Boolean = false, key: String? = "e2") =
        p.shouldStart(key, enabled, metered, allow, pos, 1000)

    @Test fun startsOnlyPastEightyPercent() {
        assertFalse(start(799)); assertTrue(start(800))
    }

    @Test fun disabledOrNoNextNeverStarts() {
        assertFalse(start(900, enabled = false)); assertFalse(start(900, key = null))
        assertFalse(p.shouldStart("e2", true, false, false, 900, 0))
    }

    @Test fun meteredNeedsOptIn() {
        assertFalse(start(900, metered = true)); assertTrue(start(900, metered = true, allow = true))
    }

    @Test fun attemptedAndCachedAreNotRestarted() {
        p.markAttempted("e2"); assertFalse(start(900))
        p.clear(); p.put("e2", "set"); assertFalse(start(900))
    }

    @Test fun takeReturnsFreshOnceThenNull() {
        p.put("e2", "set"); now = 9 * 60_000L
        assertEquals("set", p.take("e2")); assertNull(p.take("e2"))
    }

    @Test fun expiresAfterTenMinutes() {
        p.put("e2", "set"); now = 10 * 60_000L
        assertNull(p.take("e2"))
    }

    @Test fun expiredEntryAllowsNewStart() {
        p.put("e2", "set"); now = 11 * 60_000L
        assertTrue(start(900))
    }
}
