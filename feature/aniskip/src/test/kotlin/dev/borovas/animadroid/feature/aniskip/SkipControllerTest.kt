package dev.borovas.animadroid.feature.aniskip

import dev.borovas.animadroid.core.model.SkipBehavior
import dev.borovas.animadroid.core.model.SkipInterval
import dev.borovas.animadroid.core.model.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipControllerTest {
    private val times = SkipTimes(SkipInterval(10.0, 90.0), SkipInterval(1300.0, 1390.0))

    @Test fun autoSeeksOnceWithOffset() {
        val c = SkipController(times, 2, SkipBehavior.AUTO)
        assertEquals(SkipAction.None, c.onPosition(5.0))
        assertEquals(SkipAction.AutoSeek(88_000, SkipSegment.OPENING), c.onPosition(10.0))
        assertEquals(SkipAction.None, c.onPosition(20.0))
    }

    @Test fun buttonKeepsShowingUntilMarked() {
        val c = SkipController(times, 0, SkipBehavior.BUTTON)
        assertEquals(SkipAction.ShowButton(SkipSegment.OPENING, 90_000), c.onPosition(11.0))
        assertEquals(SkipAction.ShowButton(SkipSegment.OPENING, 90_000), c.onPosition(12.0))
        c.markSkipped(SkipSegment.OPENING)
        assertEquals(SkipAction.None, c.onPosition(13.0))
        assertEquals(SkipAction.None, c.onPosition(95.0))
    }

    @Test fun endingAndReset() {
        val c = SkipController(times, 0, SkipBehavior.AUTO)
        assertEquals(SkipAction.AutoSeek(1_390_000, SkipSegment.ENDING), c.onPosition(1301.0))
        c.reset()
        assertEquals(SkipAction.AutoSeek(1_390_000, SkipSegment.ENDING), c.onPosition(1301.0))
    }

    @Test fun backwardSeekRearms() {
        val c = SkipController(times, 0, SkipBehavior.AUTO)
        c.onPosition(11.0)
        assertEquals(SkipAction.None, c.onPosition(100.0))
        assertEquals(SkipAction.None, c.onPosition(3.0))
        assertEquals(SkipAction.AutoSeek(90_000, SkipSegment.OPENING), c.onPosition(10.5))
    }

    @Test fun disabledAndNullTimes() {
        val c = SkipController(times, 0, SkipBehavior.AUTO)
        c.setEnabled(false)
        assertEquals(SkipAction.None, c.onPosition(20.0))
        c.setEnabled(true)
        assertEquals(SkipAction.AutoSeek(90_000, SkipSegment.OPENING), c.onPosition(20.0))
        assertEquals(SkipAction.None, SkipController(null, 0, SkipBehavior.AUTO).onPosition(20.0))
    }
}
