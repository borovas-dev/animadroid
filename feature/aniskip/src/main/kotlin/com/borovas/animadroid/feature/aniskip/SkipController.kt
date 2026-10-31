package com.borovas.animadroid.feature.aniskip

import com.borovas.animadroid.core.model.SkipBehavior
import com.borovas.animadroid.core.model.SkipInterval
import com.borovas.animadroid.core.model.SkipTimes

enum class SkipSegment { OPENING, ENDING }

sealed interface SkipAction {
    data object None : SkipAction
    data class AutoSeek(val targetMs: Long, val segment: SkipSegment) : SkipAction
    data class ShowButton(val segment: SkipSegment, val targetMs: Long) : SkipAction
}

/** Port of ani-skip's mpv.lua skip logic. Pure and not thread-safe; drive from one thread. */
class SkipController(
    private val times: SkipTimes?,
    private val offsetSec: Int,
    private val behavior: SkipBehavior,
) {
    private var skippedOp = false
    private var skippedEd = false
    private var enabled = true
    private var lastPos = Double.NaN

    fun reset() {
        skippedOp = false; skippedEd = false; lastPos = Double.NaN
    }

    fun setEnabled(value: Boolean) {
        if (value && !enabled) reset()
        enabled = value
    }

    /** Call when the user used the button so it is not shown again for the section. */
    fun markSkipped(segment: SkipSegment) {
        if (segment == SkipSegment.OPENING) skippedOp = true else skippedEd = true
    }

    fun onPosition(posSec: Double): SkipAction {
        val prev = lastPos
        lastPos = posSec
        val t = times
        if (!enabled || t == null) return SkipAction.None
        // A backward seek before a section start re-arms it.
        if (!prev.isNaN() && posSec < prev) {
            t.opening?.let { if (posSec < it.startSec) skippedOp = false }
            t.ending?.let { if (posSec < it.startSec) skippedEd = false }
        }
        evaluate(t.opening, SkipSegment.OPENING, posSec, skippedOp)?.let { return it }
        evaluate(t.ending, SkipSegment.ENDING, posSec, skippedEd)?.let { return it }
        return SkipAction.None
    }

    private fun evaluate(i: SkipInterval?, seg: SkipSegment, t: Double, skipped: Boolean): SkipAction? {
        if (i == null || skipped) return null
        val target = i.endSec - offsetSec
        if (t < i.startSec || t >= target) return null
        val ms = (target * 1000).toLong()
        return when (behavior) {
            SkipBehavior.AUTO -> {
                markSkipped(seg); SkipAction.AutoSeek(ms, seg)
            }
            SkipBehavior.BUTTON -> SkipAction.ShowButton(seg, ms)
        }
    }
}
