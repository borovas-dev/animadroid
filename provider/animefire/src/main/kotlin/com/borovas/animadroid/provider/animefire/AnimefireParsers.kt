package com.borovas.animadroid.provider.animefire

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable internal class AfResponse<T>(val data: T, val meta: AfMeta? = null)
@Serializable internal class AfMeta(@SerialName("current_page") val currentPage: Int = 1, @SerialName("last_page") val lastPage: Int = 1)
@Serializable internal class AfAnime(
    val id: String,
    val titles: Map<String, String> = emptyMap(),
    @SerialName("poster_src") val posterSrc: String? = null,
    val synopsis: String? = null,
    val audio: String? = null,
)
@Serializable internal class AfSeason(val number: Int, @SerialName("first_episode_number") val firstEpisodeNumber: Int = 1)
@Serializable internal class AfEpisode(val id: String, val number: Double, val season: Int? = null, val title: String? = null)
@Serializable internal class AfDetails(val hero: AfAnime, val seasons: List<AfSeason> = emptyList(), val episodes: List<AfEpisode> = emptyList())
@Serializable internal class AfStream(
    val audio: String = "",
    val url: String? = null,
    @SerialName("is_offline") val isOffline: Boolean = false,
    @SerialName("is_mtl") val isMtl: Boolean = false,
)
@Serializable internal class AfPlayback(val streams: List<AfStream> = emptyList())

internal object AnimefireParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun AfAnime.title(): String =
        listOf("BR", "US", "JP").firstNotNullOfOrNull { titles[it]?.takeIf(String::isNotBlank) }
            ?: titles.values.firstOrNull { it.isNotBlank() } ?: id

    private fun AfAnime.toAnime(providerId: String) = Anime(
        id = id, providerId = providerId, title = title(), coverUrl = posterSrc?.takeIf { it.isNotBlank() }, synopsis = synopsis?.takeIf { it.isNotBlank() },
    )

    fun parseSearch(body: String, providerId: String): List<Anime> =
        json.decodeFromString<AfResponse<List<AfAnime>>>(body).data.map { it.toAnime(providerId) }

    fun parseDetails(body: String, providerId: String): Anime = json.decodeFromString<AfResponse<AfDetails>>(body).data.hero.toAnime(providerId)

    /** Episodes with a global number across seasons (season first-episode offset), ascending. */
    fun parseEpisodes(body: String): List<Episode> {
        val d = json.decodeFromString<AfResponse<AfDetails>>(body).data
        val first = d.seasons.associate { it.number to it.firstEpisodeNumber }
        return d.episodes
            .map { e -> e to (e.number + (first[e.season] ?: 1) - 1) }
            .sortedBy { it.second }
            .map { (e, n) -> Episode(id = e.id, number = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString(), title = e.title?.takeIf { it.isNotBlank() }) }
    }

    /** Playable master URL for the requested audio, or null. */
    fun parseStreamUrl(body: String, dub: Boolean): String? =
        json.decodeFromString<AfResponse<AfPlayback>>(body).data.streams
            .filter { !it.isOffline && !it.url.isNullOrBlank() && (it.audio == "dublado") == dub }
            .sortedBy { it.isMtl }.firstOrNull()?.url

    fun hasPages(body: String): Boolean = json.decodeFromString<AfResponse<kotlinx.serialization.json.JsonElement>>(body).meta
        ?.let { it.currentPage < it.lastPage } ?: false
}
