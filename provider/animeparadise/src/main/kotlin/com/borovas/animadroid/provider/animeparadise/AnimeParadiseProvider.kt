package com.borovas.animadroid.provider.animeparadise

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.SkipInterval
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.SubtitleTrack
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.core.network.CloudflareBlockedException
import com.borovas.animadroid.core.network.CloudflareDetector
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.ProviderException
import com.borovas.animadroid.provider.common.M3u8
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * animeparadise.moe: JSON API (`api.animeparadise.moe`) + an HLS proxy on `stream.animeparadise.moe`. SUB only.
 * The base URL is read through [config] per request so a remote-config refresh applies without a restart.
 * [Anime.id] is the site `link` slug; [Episode.id] is `<uid>|<anime _id>` (the stream endpoint needs both).
 */
class AnimeParadiseProvider(
    private val client: OkHttpClient,
    private val config: () -> ProviderRemoteConfig = { ProviderRemoteConfig.DEFAULT },
    /** ASS tracks come from `/stream/file/<id>` (no file extension), which the player would mis-detect as WebVTT. */
    private val includeAssSubtitles: Boolean = false,
) : AnimeProvider {
    override val id = ID
    override val displayName = "AnimeParadise"
    override val contentLanguages = setOf("en")
    override val gfwBlocked = false
    override val priority = 2

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val base get() = config().baseUrlOr(DEFAULT_BASE_URL)
    private val streamBase get() = config().param("streamBase", DEFAULT_STREAM_BASE).trimEnd('/')

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        if (mode == TranslationMode.DUB) return emptyList()
        val url = "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "POPULARITY").addQueryParameter("page", "1")
            .addQueryParameter("q", query.trim()).addQueryParameter("v", "1").build()
        return decode<SearchResponse>(get(url.toString())).data.map { it.toAnime() }
    }

    override suspend fun details(anime: Anime): Anime {
        val d = runCatching { fetchDetails(anime.id) }.getOrNull() ?: return anime
        return anime.copy(
            synopsis = anime.synopsis ?: d.synopsys?.takeIf { it.isNotBlank() },
            coverUrl = anime.coverUrl ?: d.posterImage?.best(),
            year = anime.year ?: d.animeSeason?.year,
            subEpisodes = anime.subEpisodes ?: d.episodes,
            malId = anime.malId ?: d.mappings?.myanimelist,
        )
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        if (mode == TranslationMode.DUB) throw ProviderException("AnimeParadise has no dubbed episodes")
        val origin = fetchDetails(anime.id).id
        val list = decode<EpisodesResponse>(get("$base/anime/${enc(origin)}/episode")).data
        return list.filter { it.uid != null && it.number != null }
            .map { Episode("${it.uid}|${it.origin ?: origin}", it.number!!, it.title?.takeIf(String::isNotBlank)) }
            .sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        if (mode == TranslationMode.DUB) throw ProviderException("AnimeParadise has no dubbed episodes")
        val (uid, origin) = episode.id.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
        if (origin.isNullOrEmpty()) throw ProviderException("Unsupported AnimeParadise episode id '${episode.id}'")
        val url = "$base/ep/${enc(uid)}".toHttpUrl().newBuilder().addQueryParameter("origin", origin).build()
        val ep = decode<EpisodeResponse>(get(url.toString())).data?.episode
        val token = ep?.streamLink?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("No stream for episode ${episode.number}")

        val master = "$streamBase/m3u8".toHttpUrl().newBuilder().addQueryParameter("url", token).build().toString()
        val parsed = M3u8.parseMaster(get(master), master)
        if (parsed.variants.isEmpty()) throw ProviderException("Master playlist has no variants")

        val skip = ep.skipData
        return StreamSet(
            masterUrl = master,
            variants = parsed.variants,
            headers = mapOf("Origin" to SITE_ORIGIN, "Referer" to "$SITE_ORIGIN/", "User-Agent" to ANI_CLI_USER_AGENT),
            externalAudioUrl = parsed.externalAudioUrl,
            subtitles = subtitleTracks(ep.subData),
            malId = null,
            intro = skip?.intro?.toInterval(),
            outro = skip?.outro?.toInterval(),
        )
    }

    private fun subtitleTracks(data: List<SubDto>): List<SubtitleTrack> {
        val tracks = data.mapNotNull { s ->
            val src = s.src?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val isVtt = s.type.equals("vtt", true)
            if (!isVtt && !(includeAssSubtitles && s.type.equals("ass", true))) return@mapNotNull null
            val url = if (src.startsWith("http")) src else "$base/stream/file/${enc(src)}"
            val label = s.label ?: "Unknown"
            Triple(isVtt, label, SubtitleTrack(url, LANGS[label.lowercase()], label, false))
        }
        // VTT first (ExoPlayer-native), English first within each format; the first English track is the default.
        val sorted = tracks.sortedWith(compareBy({ !it.first }, { !it.second.equals("English", true) }))
        val defaultIdx = sorted.indexOfFirst { it.second.equals("English", true) }
        return sorted.mapIndexed { i, t -> t.third.copy(isDefault = i == defaultIdx) }
    }

    private suspend fun fetchDetails(link: String): AnimeDto =
        decode<DetailsResponse>(get("$base/anime/${enc(link)}")).data ?: throw ProviderException("Anime '$link' not found")

    private fun enc(segment: String) = okhttp3.HttpUrl.Builder().scheme("https").host("x").addPathSegment(segment).build().encodedPath.removePrefix("/")

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        throw ProviderException("Unexpected AnimeParadise response", e)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", ANI_CLI_USER_AGENT)
            .header("Origin", SITE_ORIGIN).header("Referer", "$SITE_ORIGIN/").header("Accept", "application/json, */*").build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (CloudflareDetector.isChallenge(resp.code, resp.headers) { text }) throw CloudflareChallengeException(url)
                if (!resp.isSuccessful) throw ProviderException("Request failed: HTTP ${resp.code} from $url")
                text
            }
        } catch (e: CloudflareBlockedException) {
            throw CloudflareChallengeException(e.url)
        } catch (e: IOException) {
            throw ProviderException("Connection error: could not fetch $url (${e.message})", e)
        }
    }

    private fun AnimeDto.toAnime() = Anime(
        id = link ?: id,
        providerId = ID,
        title = title ?: alternativeTitle?.english ?: alternativeTitle?.romaji ?: link ?: id,
        coverUrl = posterImage?.best(),
        subEpisodes = episodes,
        year = animeSeason?.year,
    )

    private fun SkipDto.toInterval(): SkipInterval? =
        if (end > start && end > 0) SkipInterval(start, end) else null

    companion object {
        const val ID = "animeparadise"
        const val DEFAULT_BASE_URL = "https://api.animeparadise.moe"
        const val DEFAULT_STREAM_BASE = "https://stream.animeparadise.moe"
        const val SITE_ORIGIN = "https://www.animeparadise.moe"
        private val LANGS = mapOf("english" to "en", "italian" to "it", "portuguese" to "pt", "spanish" to "es")
    }
}
