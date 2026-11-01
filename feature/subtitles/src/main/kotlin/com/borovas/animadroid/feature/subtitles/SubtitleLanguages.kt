package com.borovas.animadroid.feature.subtitles

import java.util.Locale

/** Language code handling shared by the picker, settings and the translator (pure JVM, no ML Kit types). */
object SubtitleLanguages {
    private val BIBLIOGRAPHIC = mapOf(
        "chi" to "zh", "zho" to "zh", "ger" to "de", "fre" to "fr", "dut" to "nl", "gre" to "el", "cze" to "cs",
        "slo" to "sk", "rum" to "ro", "per" to "fa", "ice" to "is", "mac" to "mk", "may" to "ms", "bur" to "my",
        "geo" to "ka", "arm" to "hy", "baq" to "eu", "wel" to "cy", "alb" to "sq",
    )
    private val byIso3: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).isO3Language } + BIBLIOGRAPHIC
    }
    private val byEnglishName: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase() }
    }

    /** "en", "eng", "en-US", "pt_BR", "zh-Hans" or "English" to a two-letter code; null if unrecognised. */
    fun normalize(tagOrName: String?): String? {
        val s = tagOrName?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || s == "und") return null
        val primary = s.substringBefore('-').substringBefore('_')
        return when {
            primary.length == 2 && primary.all { it in 'a'..'z' } -> primary
            primary.length == 3 -> byIso3[primary]
            else -> byEnglishName[primary]
        }
    }

    /** Name of [code] in its own language, e.g. "zh" to "中文", "pl" to "polski" (capitalised). */
    fun displayName(code: String): String {
        val l = Locale.forLanguageTag(code)
        return l.getDisplayLanguage(l).replaceFirstChar { it.titlecase(l) }.ifBlank { code }
    }
}
