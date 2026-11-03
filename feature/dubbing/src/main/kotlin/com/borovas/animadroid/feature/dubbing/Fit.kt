package com.borovas.animadroid.feature.dubbing

sealed interface FitResult {
    /** Fits the window (possibly after speeding up). */
    data class Play(val speed: Float) : FitResult
    /** Too long even at max speed, but short enough to run into the gap before the next cue. */
    data class PlayOverflow(val speed: Float) : FitResult
    /** Hopeless; skip the cue. */
    data object Drop : FitResult
}

/** Speech longer than this multiple of the window is dropped (ratio of natural duration to window). */
const val DROP_RATIO = 1.8f

/**
 * Decides how to play [speechDurationMs] of natural-rate speech in [windowMs].
 * ratio <= 1 -> Play(1x); ratio <= maxSpeed -> Play(ratio); ratio <= [DROP_RATIO] -> PlayOverflow(maxSpeed); else Drop.
 */
fun fitSpeed(speechDurationMs: Long, windowMs: Long, maxSpeed: Float = 1.35f): FitResult {
    if (speechDurationMs <= 0 || windowMs <= 0) return FitResult.Drop
    val ratio = speechDurationMs.toFloat() / windowMs
    return when {
        ratio <= 1f -> FitResult.Play(1f)
        ratio <= maxSpeed -> FitResult.Play(ratio)
        ratio <= DROP_RATIO -> FitResult.PlayOverflow(maxSpeed)
        else -> FitResult.Drop
    }
}
