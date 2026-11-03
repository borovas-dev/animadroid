package com.borovas.animadroid.provider.animeparadise

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class SearchResponse(val data: List<AnimeDto> = emptyList())
@Serializable internal data class DetailsResponse(val data: AnimeDto? = null)
@Serializable internal data class EpisodesResponse(val data: List<EpisodeDto> = emptyList())
@Serializable internal data class EpisodeResponse(val data: EpisodeData? = null)
@Serializable internal data class EpisodeData(val episode: EpisodeDto? = null)

@Serializable
internal data class AnimeDto(
    @SerialName("_id") val id: String,
    val title: String? = null,
    val link: String? = null,
    val episodes: Int? = null,
    val synopsys: String? = null,
    val alternativeTitle: AltTitle? = null,
    val posterImage: Poster? = null,
    val animeSeason: Season? = null,
    val mappings: Mappings? = null,
)

@Serializable internal data class AltTitle(val english: String? = null, val native: String? = null, val romaji: String? = null)
@Serializable internal data class Season(val year: Int? = null)
@Serializable internal data class Mappings(val myanimelist: Int? = null)
@Serializable
internal data class Poster(val large: String? = null, val medium: String? = null, val original: String? = null) {
    fun best() = large ?: medium ?: original
}

@Serializable
internal data class EpisodeDto(
    val uid: String? = null,
    val number: String? = null,
    val title: String? = null,
    val origin: String? = null,
    val streamLink: String? = null,
    val subData: List<SubDto> = emptyList(),
    val skipData: SkipData? = null,
)

@Serializable internal data class SubDto(val src: String? = null, val label: String? = null, val type: String? = null)
@Serializable internal data class SkipData(val intro: SkipDto? = null, val outro: SkipDto? = null)
@Serializable internal data class SkipDto(val start: Double = 0.0, val end: Double = 0.0)
