package com.borovas.animadroid.provider.anilibria

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.SkipInterval
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.ProviderException
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.provider.common.HostHttp
import com.borovas.animadroid.provider.common.withMirrors
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Russian provider backed by the official AniLiberty JSON API (https://anilibria.top/api/docs/v1).
 * The release is voiced/subbed in Russian as published, so only [TranslationMode.SUB] is served; DUB yields nothing.
 */
class AnilibriaProvider(
    client: OkHttpClient,
    private val config: () -> ProviderRemoteConfig = { ProviderRemoteConfig.DEFAULT },
) : AnimeProvider {
    override val id = ID
    override val displayName = "AniLibria"
    override val contentLanguages = setOf("ru")
    override val gfwBlocked = false
    override val priority = 0

    private val http = HostHttp(client, acceptLanguage = "ru,en;q=0.8")
    private fun bases() = config().mirrorsOr(listOf(DEFAULT_BASE_URL))
    private fun apiPath() = "/" + config().param("apiPath", DEFAULT_API_PATH).trim('/')

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        if (mode == TranslationMode.DUB || query.isBlank()) return emptyList()
        return withMirrors(bases()) { searchAt(it, query) }
    }

    private suspend fun searchAt(origin: String, query: String): List<Anime> {
        val base = origin + apiPath()
        val url = "$base/app/search/releases".toHttpUrl().newBuilder().addQueryParameter("query", query.trim()).build()
        return parse<List<Release>>(http.get(url.toString())).map { it.toAnime(origin) }
    }

    override suspend fun details(anime: Anime): Anime {
        val (origin, r) = runCatching { withMirrors(bases()) { release(it, anime.id) } }.getOrNull() ?: return anime
        val full = r.toAnime(origin)
        return anime.copy(
            coverUrl = anime.coverUrl ?: full.coverUrl, synopsis = anime.synopsis ?: full.synopsis,
            subEpisodes = anime.subEpisodes ?: full.subEpisodes, year = anime.year ?: full.year, malId = anime.malId ?: full.malId,
        )
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        if (mode == TranslationMode.DUB) return emptyList()
        return withMirrors(bases()) { release(it, anime.id) }.second.episodes.sortedBy { it.ordinal }
            .map { Episode(id = it.id, number = numberOf(it.ordinal), title = it.name?.takeIf(String::isNotBlank)) }
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        if (mode == TranslationMode.DUB) throw ProviderException("AniLibria has no separate dub (Russian voice-over only)")
        val (origin, r) = withMirrors(bases()) { release(it, anime.id) }
        val ep = r.episodes.firstOrNull { it.id == episode.id }
            ?: throw ProviderException("Episode ${episode.number} not found in AniLibria release ${anime.id}")
        val variants = listOf(1080 to ep.hls1080, 720 to ep.hls720, 480 to ep.hls480)
            .mapNotNull { (q, u) -> u?.takeIf(String::isNotBlank)?.let { StreamVariant(q, it) } }
        if (variants.isEmpty()) throw ProviderException("No HLS source for episode ${episode.number}")
        return StreamSet(
            masterUrl = variants.first().url,
            variants = variants,
            headers = mapOf("Referer" to "$origin/", "User-Agent" to ANI_CLI_USER_AGENT),
            malId = r.mal?.id,
            intro = ep.opening.toInterval(),
            outro = ep.ending.toInterval(),
        )
    }

    private suspend fun release(origin: String, id: String): Pair<String, Release> {
        if (id.isBlank() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) throw ProviderException("Bad AniLibria id '$id'")
        return origin to parse<Release>(http.get("$origin${apiPath()}/anime/releases/$id"))
    }

    private fun Release.toAnime(origin: String) = Anime(
        id = id.toString(), providerId = ID,
        title = name?.main?.takeIf(String::isNotBlank) ?: name?.english ?: alias ?: id.toString(),
        coverUrl = poster?.let { absolute(origin, it.src ?: it.preview ?: it.thumbnail) },
        synopsis = description?.trim()?.takeIf(String::isNotEmpty),
        subEpisodes = episodesTotal ?: episodes.size.takeIf { it > 0 },
        year = year, malId = mal?.id,
    )

    private fun absolute(origin: String, path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> origin + (if (path.startsWith("/")) path else "/$path")
    }

    private inline fun <reified T> parse(text: String): T = try {
        JSON.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw ProviderException("Unexpected AniLiberty response", e)
    } catch (e: IllegalArgumentException) {
        throw ProviderException("Unexpected AniLiberty response", e)
    }

    private fun numberOf(o: Double) = if (o == o.toLong().toDouble()) o.toLong().toString() else o.toString()
    private fun Skip?.toInterval(): SkipInterval? =
        if (this?.start != null && stop != null && stop > start) SkipInterval(start.toDouble(), stop.toDouble()) else null

    @Serializable private data class Release(
        val id: Int,
        val year: Int? = null,
        val name: Name? = null,
        val alias: String? = null,
        val description: String? = null,
        @kotlinx.serialization.SerialName("episodes_total") val episodesTotal: Int? = null,
        val poster: Poster? = null,
        val mal: Mal? = null,
        val episodes: List<Ep> = emptyList(),
    )
    @Serializable private data class Name(val main: String? = null, val english: String? = null)
    @Serializable private data class Poster(val src: String? = null, val preview: String? = null, val thumbnail: String? = null)
    @Serializable private data class Mal(val id: Int? = null)
    @Serializable private data class Skip(val start: Int? = null, val stop: Int? = null)
    @Serializable private data class Ep(
        val id: String,
        val name: String? = null,
        val ordinal: Double = 0.0,
        val opening: Skip? = null,
        val ending: Skip? = null,
        @kotlinx.serialization.SerialName("hls_480") val hls480: String? = null,
        @kotlinx.serialization.SerialName("hls_720") val hls720: String? = null,
        @kotlinx.serialization.SerialName("hls_1080") val hls1080: String? = null,
    )

    companion object {
        const val ID = "anilibria"
        const val DEFAULT_BASE_URL = "https://anilibria.top"
        const val DEFAULT_API_PATH = "/api/v1"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}
