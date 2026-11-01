package com.borovas.animadroid.feature.subtitles

/**
 * Normalises raw cue text from WebVTT / SRT / ASS into plain text with `\n` breaks and only `<i>`, `<b>`, `<u>`.
 * Scanned by hand rather than with regexes: Android's ICU regex engine rejects some patterns the JVM accepts.
 */
object CueMarkup {
    private val BASIC_TAGS = setOf("i", "b", "u", "/i", "/b", "/u")

    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                // ASS / SSA override block such as {\an8} or {\i1\pos(1,2)}; also used inside SRT.
                c == '{' && i + 1 < raw.length && raw[i + 1] == '\\' -> {
                    val end = raw.indexOf('}', i)
                    i = if (end < 0) raw.length else end + 1
                }
                c == '\\' && i + 1 < raw.length && (raw[i + 1] == 'N' || raw[i + 1] == 'n') -> { out.append('\n'); i += 2 }
                c == '\\' && i + 1 < raw.length && raw[i + 1] == 'h' -> { out.append(' '); i += 2 }
                c == '<' -> {
                    val end = raw.indexOf('>', i)
                    if (end < 0) { out.append(c); i++ } else {
                        val name = raw.substring(i + 1, end).trim().lowercase()
                        if (name in BASIC_TAGS) out.append('<').append(name).append('>')
                        i = end + 1
                    }
                }
                c == '&' -> {
                    val end = raw.indexOf(';', i)
                    val entity = if (end in (i + 1)..(i + 7)) raw.substring(i + 1, end) else null
                    val decoded = when (entity) {
                        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                        "nbsp" -> " "; "lrm", "rlm" -> ""
                        else -> null
                    }
                    if (decoded != null) { out.append(decoded); i = end + 1 } else { out.append(c); i++ }
                }
                c == '\r' -> i++
                else -> { out.append(c); i++ }
            }
        }
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Text without any tags, for measuring and tests. */
    fun plain(text: String): String = stripTags(text)

    internal fun stripTags(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val tag = tagAt(text, i)
            if (tag != null) i += tag.length else sb.append(text[i++])
        }
        return sb.toString()
    }

    /** The basic tag starting at [i] (e.g. `<i>` or `</b>`), or null. */
    internal fun tagAt(text: String, i: Int): String? {
        if (text[i] != '<') return null
        val end = text.indexOf('>', i)
        if (end < 0 || end - i > 4) return null
        val tag = text.substring(i, end + 1)
        return tag.takeIf { it.substring(1, it.length - 1) in BASIC_TAGS }
    }
}

/** A run of text with basic styling, ready for a Spannable. */
data class StyledText(val text: String, val spans: List<Span>) {
    enum class Kind { ITALIC, BOLD, UNDERLINE }
    data class Span(val kind: Kind, val start: Int, val end: Int)

    companion object {
        /** Parses normalised markup (see [CueMarkup]) into plain text plus style ranges. */
        fun parse(markup: String): StyledText {
            val sb = StringBuilder()
            val open = HashMap<Kind, Int>()
            val spans = ArrayList<Span>()
            var i = 0
            while (i < markup.length) {
                val tag = CueMarkup.tagAt(markup, i)
                if (tag == null) { sb.append(markup[i++]); continue }
                i += tag.length
                val closing = tag[1] == '/'
                val kind = when (tag.trim('<', '>', '/')) { "i" -> Kind.ITALIC; "b" -> Kind.BOLD; else -> Kind.UNDERLINE }
                if (closing) {
                    open.remove(kind)?.let { start -> if (sb.length > start) spans += Span(kind, start, sb.length) }
                } else open[kind] = sb.length
            }
            open.forEach { (kind, start) -> if (sb.length > start) spans += Span(kind, start, sb.length) }
            return StyledText(sb.toString(), spans)
        }
    }
}
