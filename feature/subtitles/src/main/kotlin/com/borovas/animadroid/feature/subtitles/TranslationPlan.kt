package com.borovas.animadroid.feature.subtitles

/**
 * How one cue is split into translatable units. Leading/trailing basic tags are kept verbatim around the translated
 * body, interior tags are dropped. Dialogue cues ("- a" / "- b") keep one unit per line; other multi-line cues are
 * joined into one sentence so the translator sees whole thoughts, and the view re-wraps them.
 */
class CuePlan(val units: List<Unit>) {
    data class Unit(val prefix: String, val body: String, val suffix: String)

    /** Rebuilds cue text from translations of each unit's body (same order as [units]). */
    fun render(translated: List<String>): String =
        units.indices.joinToString("\n") { i -> units[i].prefix + translated[i].trim() + units[i].suffix }

    companion object {
        fun of(markup: String): CuePlan {
            val lines = markup.split('\n').filter { it.isNotBlank() }
            if (lines.isEmpty()) return CuePlan(emptyList())
            val dialogue = lines.size > 1 && lines.all { CueMarkup.stripTags(it).trimStart().firstOrNull() in DASHES }
            return if (dialogue) CuePlan(lines.map { unit(it) }) else CuePlan(listOf(unit(lines.joinToString(" "))))
        }

        private val DASHES = setOf('-', '–', '—')

        private fun unit(text: String): Unit {
            var start = 0
            val prefix = StringBuilder()
            while (start < text.length) {
                val t = CueMarkup.tagAt(text, start) ?: break
                prefix.append(t); start += t.length
            }
            var end = text.length
            val suffix = ArrayDeque<String>()
            while (end > start && text[end - 1] == '>') {
                val open = text.lastIndexOf('<', end - 1)
                val t = if (open >= start) CueMarkup.tagAt(text, open) else null
                if (t == null || open + t.length != end) break
                suffix.addFirst(t); end = open
            }
            return Unit(prefix.toString(), CueMarkup.stripTags(text.substring(start, end)).trim(), suffix.joinToString(""))
        }
    }
}

/** Only bodies containing a letter are worth sending to the translator ("♪", "..." are not). */
internal fun needsTranslation(body: String): Boolean = body.any { it.isLetter() }

/** Ordering and chunking of cue indices so translation runs ahead of playback. */
object CueBatcher {
    fun chunks(size: Int, batchSize: Int): List<IntRange> =
        (0 until size step batchSize).map { it until minOf(it + batchSize, size) }

    /** Next chunk to translate: the first pending one ending after the playback position, else the first pending. */
    fun nextChunk(cues: List<SubtitleCue>, chunks: List<IntRange>, done: Set<Int>, positionMs: Long): Int? {
        val pending = chunks.indices.filter { it !in done }
        if (pending.isEmpty()) return null
        return pending.firstOrNull { cues[chunks[it].last].endMs > positionMs } ?: pending.first()
    }
}
