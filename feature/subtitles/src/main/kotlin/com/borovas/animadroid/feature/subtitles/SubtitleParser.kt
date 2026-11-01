package com.borovas.animadroid.feature.subtitles

/** Parses WebVTT, SRT and ASS/SSA into cues with their timing untouched (text goes through [CueMarkup]). */
object SubtitleParser {
    fun parse(content: String): List<SubtitleCue> {
        val text = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = if (text.contains("[Events]", ignoreCase = true)) parseAss(text) else parseTimed(text)
        return cues.filter { it.text.isNotEmpty() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    /** WebVTT and SRT share the "start --> end" block layout (SRT uses a comma for millis). */
    private fun parseTimed(text: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        for (block in text.split("\n\n")) {
            val lines = block.split('\n')
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) continue
            val parts = lines[timing].split("-->")
            val start = parseTime(parts[0].trim()) ?: continue
            val end = parseTime(parts[1].trim().substringBefore(' ')) ?: continue
            val body = lines.drop(timing + 1).joinToString("\n")
            out += SubtitleCue(start, end, CueMarkup.normalize(body))
        }
        return out
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        var inEvents = false
        var columns: List<String> = emptyList()
        val out = ArrayList<SubtitleCue>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                columns = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (line.startsWith("Dialogue:", ignoreCase = true) && columns.isNotEmpty()) {
                val fields = line.substringAfter(':').trimStart().split(',', limit = columns.size)
                if (fields.size < columns.size) continue
                val start = parseTime(fields[columns.indexOf("start").takeIf { it >= 0 } ?: continue].trim()) ?: continue
                val end = parseTime(fields[columns.indexOf("end").takeIf { it >= 0 } ?: continue].trim()) ?: continue
                val body = fields[columns.indexOf("text").takeIf { it >= 0 } ?: continue]
                if (body.contains("\\p1")) continue // vector drawing, not text
                out += SubtitleCue(start, end, CueMarkup.normalize(body))
            }
        }
        return out
    }

    /** `[hh:]mm:ss[.,]fff` (also ASS `h:mm:ss.cc`) to milliseconds. */
    internal fun parseTime(s: String): Long? {
        val parts = s.split(':')
        if (parts.size !in 2..3) return null
        val secPart = parts.last().replace(',', '.')
        val whole = secPart.substringBefore('.').toLongOrNull() ?: return null
        val frac = secPart.substringAfter('.', "")
        if (frac.any { !it.isDigit() }) return null
        val millis = if (frac.isEmpty()) 0L else (frac + "00").take(3).toLong()
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + min) * 60 + whole) * 1000 + millis
    }
}
