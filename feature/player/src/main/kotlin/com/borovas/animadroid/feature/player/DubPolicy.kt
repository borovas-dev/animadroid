package com.borovas.animadroid.feature.player

import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.feature.subtitles.SubtitleLanguages
import com.borovas.animadroid.provider.api.ProviderException

/** Pure decision for the opt-in generated voice-over; kept free of Android types so it is unit-testable. */
internal object DubPolicy {
    /**
     * Voice-over runs only when the switch is on, the user chose DUB, no recorded dub exists for the target language,
     * there are cues to read and the language is known. SUB mode never runs it.
     */
    fun shouldRun(dubEnabled: Boolean, mode: TranslationMode, recordedDubAvailable: Boolean, hasCues: Boolean, targetLang: String?): Boolean =
        dubEnabled && mode == TranslationMode.DUB && !recordedDubAvailable && hasCues && !targetLang.isNullOrBlank()

    /** Target language L: the normalised subtitle language, else the chain's effective content language, else "en". */
    fun targetLanguage(subtitleLanguage: String, effectiveContentLanguage: String): String =
        SubtitleLanguages.normalize(subtitleLanguage) ?: SubtitleLanguages.normalize(effectiveContentLanguage) ?: "en"

    /** Known without any request: the provider reports zero dubbed episodes (null = unknown, so it is still tried). */
    fun dubKnownMissing(mode: TranslationMode, dubEpisodes: Int?): Boolean = mode == TranslationMode.DUB && dubEpisodes == 0

    /**
     * A recorded dub that did resolve is in the provider's own content language (Shinden = pl, HiAnime/KickAss = en, ...).
     * When voice-over is enabled and that is not [targetLang], it does not count as a dub "in L" and SUB + voice-over is used.
     * With voice-over off a resolved dub is always played as before.
     */
    fun dubLanguageMismatch(dubEnabled: Boolean, providerLanguages: Set<String>?, targetLang: String): Boolean =
        dubEnabled && providerLanguages != null && targetLang !in providerLanguages

    /** Only "no dub players/streams" ([ProviderException]) falls back; Cloudflare, network and other errors propagate. */
    fun isNoDubFailure(e: Throwable): Boolean = e is ProviderException

    /** Player volume while ducked / not: never louder than the user's own level (mute stays muted). */
    fun volume(userVolume: Float, ducked: Boolean): Float = if (ducked) minOf(userVolume, DUCK_VOLUME) else userVolume

    const val DUCK_VOLUME = 0.25f

    /** Extrapolates the playback position from the last sampled one. */
    fun extrapolate(sampleMs: Long, sampledAtMs: Long, nowMs: Long, playing: Boolean, speed: Float): Long =
        if (playing) sampleMs + ((nowMs - sampledAtMs).coerceAtLeast(0) * speed).toLong() else sampleMs
}
