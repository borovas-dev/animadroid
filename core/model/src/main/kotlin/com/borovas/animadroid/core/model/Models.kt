package com.borovas.animadroid.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TranslationMode { SUB, DUB }

/** A show as returned by a provider search. [id] is provider-specific (e.g. hianime slug). */
@Serializable
data class Anime(
    val id: String,
    val providerId: String,
    val title: String,
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val subEpisodes: Int? = null,
    val dubEpisodes: Int? = null,
    val year: Int? = null,
    val malId: Int? = null,
)

@Serializable
data class Episode(
    /** Provider episode id used to fetch streams. */
    val id: String,
    /** Human episode number as string ("1", "12.5"), like ani-cli ep_no. */
    val number: String,
    val title: String? = null,
)

@Serializable
data class StreamVariant(
    /** Height in px (1080), or null when unknown. */
    val quality: Int?,
    val url: String,
)

@Serializable
data class SubtitleTrack(val url: String, val language: String?, val label: String?, val isDefault: Boolean)

/** Everything the player needs to play one episode. */
@Serializable
data class StreamSet(
    /** Master playlist (if HLS) so ExoPlayer can do ABR. */
    val masterUrl: String?,
    /** Sorted best → worst. */
    val variants: List<StreamVariant>,
    val headers: Map<String, String>,
    val externalAudioUrl: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val malId: Int? = null,
    /** Provider-supplied intro/outro (hianime embed `skip.intro` / `skip.outro`); fallback when ani-skip has no data. */
    val intro: SkipInterval? = null,
    val outro: SkipInterval? = null,
    /**
     * Thumbnail-sprite WebVTT (hianime embed `sprite_vtt`; cues point at `sprite.jpg#xywh=x,y,w,h`), used for the
     * seek-bar scrub preview. Null when the provider has none (survey: ~1 in 40 embeds) - the player then falls back to
     * decoding frames from the stream itself.
     */
    val spriteVttUrl: String? = null,
    /** Human-readable audio/subtitle language of this source (e.g. "Polish dub/lektor", "JP audio + PL subs"); null when the provider has a single fixed language. */
    val languageLabel: String? = null,
)

@Serializable
data class SkipInterval(val startSec: Double, val endSec: Double)

@Serializable
data class SkipTimes(val opening: SkipInterval?, val ending: SkipInterval?)

enum class QualityPreference { BEST, WORST, P1080, P720, P480, P360 }
