package com.borovas.animadroid.feature.subtitles

import kotlinx.serialization.Serializable

/**
 * One timed subtitle line. [text] is normalised markup: plain text with `\n` line breaks and only the basic
 * `<i>`, `<b>` and `<u>` tags (see [CueMarkup]).
 */
@Serializable
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Cue lookup by playback position; [cues] must be sorted by start time. */
fun List<SubtitleCue>.activeAt(positionMs: Long): SubtitleCue? {
    if (isEmpty()) return null
    // Last cue starting at or before the position; cues may overlap, so also look a few back.
    var lo = 0
    var hi = size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
    }
    var i = idx
    var steps = 0
    while (i >= 0 && steps < 8) {
        val c = this[i]
        if (positionMs < c.endMs) return c
        i--; steps++
    }
    return null
}
