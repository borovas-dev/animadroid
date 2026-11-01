package com.borovas.animadroid.feature.subtitles

import com.borovas.animadroid.core.model.SubtitleTrack

sealed interface SubtitleDecision {
    /** The provider already offers [track] in the preferred language: select it. */
    data class UseProvided(val track: SubtitleTrack) : SubtitleDecision
    /** Translate [track] (in [sourceLang]) to the preferred language. */
    data class Translate(val track: SubtitleTrack, val sourceLang: String) : SubtitleDecision
    data object None : SubtitleDecision
}

object TrackPicker {
    const val DEFAULT_SOURCE = "en"

    /** Language a provider track declares (code, else label), or null when it says nothing usable. */
    fun languageOf(t: SubtitleTrack): String? = SubtitleLanguages.normalize(t.language) ?: SubtitleLanguages.normalize(t.label)

    private fun isRealTrack(t: SubtitleTrack) = t.label?.contains("thumbnail", ignoreCase = true) != true

    fun decide(tracks: List<SubtitleTrack>, preferred: String, translate: Boolean, canTranslate: (String) -> Boolean = { true }): SubtitleDecision {
        val target = SubtitleLanguages.normalize(preferred) ?: return SubtitleDecision.None
        val real = tracks.filter(::isRealTrack)
        real.firstOrNull { languageOf(it) == target }?.let { return SubtitleDecision.UseProvided(it) }
        if (!translate || !canTranslate(target) || real.isEmpty()) return SubtitleDecision.None
        // Best source: English, else a default-flagged track, else the first; unknown language is assumed English.
        val source = real.firstOrNull { languageOf(it) == DEFAULT_SOURCE }
            ?: real.firstOrNull { it.isDefault }
            ?: real.first()
        val sourceLang = languageOf(source) ?: DEFAULT_SOURCE
        if (!canTranslate(sourceLang)) return SubtitleDecision.None
        return SubtitleDecision.Translate(source, sourceLang)
    }
}
