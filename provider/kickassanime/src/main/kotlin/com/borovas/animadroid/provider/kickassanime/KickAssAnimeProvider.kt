package com.borovas.animadroid.provider.kickassanime

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * KickAssAnime (kaa.lt): JSON API on the site plus the `krussdomi.com` cat-player, whose page embeds the HLS manifest and
 * soft WebVTT subtitles. SUB = `ja-JP` episodes, DUB = `en-US` episodes (the episode slug differs per language, so
 * [Episode.id] is the full `ep-<number>-<slug>` path segment). The base URL is read through [config] per request.
 *
 * The master playlist has one audio rendition per language and video-only variants, and the segments (really MPEG-TS
 * disguised as `.jpg`) need an `Origin` header. So the stream is exposed as variants plus an explicit
 * [StreamSet.externalAudioUrl] (English for DUB, Japanese for SUB) rather than as the master, which is how the existing
 * player/download pipeline picks an audio language; headers carry Origin/Referer/UA for playlists, segments and subtitles.
 */
class KickAssAnimeProvider(
    private val client: OkHttpClient,
    private val config: () -> ProviderRemoteConfig = { ProviderRemoteConfig.DEFAULT },
) : AnimeProvider {
    override val id = ID
    override val displayName = "KickAssAnime"
    override val contentLanguages = setOf("en")
    override val gfwBlocked = false
    override val priority = 1

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val base get() = config().baseUrlOr(DEFAULT_BASE_URL)

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        if (query.isBlank()) return emptyList()
        val body = json.encodeToString(SearchRequest.serializer(), SearchRequest(query.trim()))
        val result = decode<SearchResponse>(request(Request.Builder().url("$base/api/fsearch").post(body.toRequestBody(JSON_TYPE)))).result
        val wanted = lang(mode)
        return result.filter { it.slug.isNotBlank() && (wanted in it.locales || (mode == TranslationMode.SUB && it.locales.isEmpty())) }.map { it.toAnime() }
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        val lang = lang(mode)
        val first = episodePage(anime.id, 1, lang)
        val pages = first.pages.size.coerceAtLeast(1)
        val all = first.result + (2..pages).flatMap { episodePage(anime.id, it, lang).result }
        return all.filter { it.slug.isNotBlank() }
            .distinctBy { it.slug }
            .sortedBy { it.episodeNumber }
            .map { Episode(id = "ep-${it.episodeString ?: numberOf(it.episodeNumber)}-${it.slug}", number = it.episodeString ?: numberOf(it.episodeNumber), title = it.title?.takeIf(String::isNotBlank)) }
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        val showSlug = segment(anime.id)
        val epSegment = segment(episode.id)
        val ep = decode<EpisodeResponse>(get("$base/api/show/$showSlug/episode/$epSegment"))
        if (mode == TranslationMode.DUB && ep.language != null && ep.language != LANG_DUB) {
            throw ProviderException("KickAssAnime has no English dub for episode ${episode.number}")
        }
        val server = ep.servers.firstOrNull { it.src.contains("/cat-player/player") && !it.src.contains("type=dash") }
            ?: throw ProviderException("No supported KickAssAnime server for episode ${episode.number}")
        val playerUrl = server.src.toHttpUrl()
        val origin = playerUrl.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        val headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to USER_AGENT)

        val page = get(server.src, headers).replace("&quot;", "\"")
        val manifest = MANIFEST.find(page)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: throw ProviderException("No stream found on the KickAssAnime player page")
        val master = get(manifest, headers)
        val parsed = M3u8.parseMaster(master, manifest)
        if (parsed.variants.isEmpty()) throw ProviderException("KickAssAnime master playlist has no variants")

        val audio = if (mode == TranslationMode.DUB) {
            audioRendition(master, manifest, setOf("eng", "en"))
                ?: throw ProviderException("KickAssAnime has no English dub for episode ${episode.number}")
        } else {
            audioRendition(master, manifest, setOf("jpn", "ja")) ?: parsed.externalAudioUrl
        }
        return StreamSet(
            masterUrl = if (audio == null) manifest else null,
            variants = parsed.variants,
            headers = headers,
            externalAudioUrl = audio,
            subtitles = subtitleTracks(page),
            languageLabel = if (mode == TranslationMode.DUB) "English dub" else "Japanese audio + subs",
        )
    }

    private fun subtitleTracks(page: String): List<SubtitleTrack> {
        val tracks = SUBTITLE.findAll(page).map { m ->
            val (lang, name, src) = m.destructured
            SubtitleTrack(src.replace("\\/", "/"), lang, name, false)
        }.toList()
        val sorted = tracks.sortedBy { it.language != "en" }
        val def = sorted.indexOfFirst { it.language == "en" }
        return sorted.mapIndexed { i, t -> if (i == def) t.copy(isDefault = true) else t }
    }

    /** First `#EXT-X-MEDIA:TYPE=AUDIO` rendition whose LANGUAGE is in [languages], resolved against [masterUrl]. */
    private fun audioRendition(master: String, masterUrl: String, languages: Set<String>): String? =
        master.lineSequence().map { it.trim() }.filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
            .firstNotNullOfOrNull { line ->
                val lang = ATTR_LANGUAGE.find(line)?.groupValues?.get(1)?.lowercase()
                val uri = ATTR_URI.find(line)?.groupValues?.get(1)
                if (lang in languages && !uri.isNullOrBlank()) M3u8.resolve(masterUrl, uri) else null
            }

    private suspend fun episodePage(showSlug: String, page: Int, lang: String): EpisodesResponse {
        val url = "$base/api/show/${segment(showSlug)}/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString()).addQueryParameter("lang", lang).build()
        return decode(get(url.toString()))
    }

    private fun Hit.toAnime() = Anime(
        id = slug, providerId = ID,
        title = titleEn?.takeIf(String::isNotBlank) ?: title?.takeIf(String::isNotBlank) ?: slug,
        coverUrl = poster?.hq?.takeIf(String::isNotBlank)?.let { "$base/image/poster/$it.webp" },
        synopsis = synopsis?.trim()?.takeIf(String::isNotEmpty),
        subEpisodes = episodeCount?.takeIf { it > 0 },
        year = year,
    )

    private fun lang(mode: TranslationMode) = if (mode == TranslationMode.DUB) LANG_DUB else LANG_SUB

    private fun segment(s: String): String {
        if (s.isBlank() || !s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) throw ProviderException("Bad KickAssAnime id '$s'")
        return s
    }

    private fun numberOf(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        throw ProviderException("Unexpected KickAssAnime response", e)
    }

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }, headers.isEmpty())

    private suspend fun request(builder: Request.Builder, siteHeaders: Boolean = true): String = withContext(Dispatchers.IO) {
        val req = builder.apply {
            if (siteHeaders) { header("User-Agent", ANI_CLI_USER_AGENT); header("Accept", "application/json, */*") }
        }.build()
        val url = req.url.toString()
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

    @Serializable private data class SearchRequest(val query: String)
    @Serializable private data class SearchResponse(val result: List<Hit> = emptyList())
    @Serializable private data class Image(val hq: String? = null, val sm: String? = null)
    @Serializable private data class Hit(
        val slug: String = "",
        val title: String? = null,
        @kotlinx.serialization.SerialName("title_en") val titleEn: String? = null,
        val synopsis: String? = null,
        val year: Int? = null,
        val locales: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("episode_count") val episodeCount: Int? = null,
        val poster: Image? = null,
    )
    @Serializable private data class EpisodesResponse(val pages: List<PageInfo> = emptyList(), val result: List<Ep> = emptyList())
    @Serializable private data class PageInfo(val number: Int = 0)
    @Serializable private data class Ep(
        val slug: String = "",
        val title: String? = null,
        @kotlinx.serialization.SerialName("episode_number") val episodeNumber: Double = 0.0,
        @kotlinx.serialization.SerialName("episode_string") val episodeString: String? = null,
    )
    @Serializable private data class EpisodeResponse(val language: String? = null, val servers: List<Server> = emptyList())
    @Serializable private data class Server(val name: String = "", val src: String = "")

    companion object {
        const val ID = "kickassanime"
        const val DEFAULT_BASE_URL = "https://kaa.lt"
        private const val LANG_SUB = "ja-JP"
        private const val LANG_DUB = "en-US"
        private const val USER_AGENT = ANI_CLI_USER_AGENT
        private val JSON_TYPE = "application/json".toMediaType()
        private val MANIFEST = Regex("\"manifest\":\\[0,\"([^\"]+)\"\\]")
        private val SUBTITLE = Regex("\"language\":\\[0,\"([^\"]*)\"\\],\"name\":\\[0,\"([^\"]*)\"\\],\"src\":\\[0,\"([^\"]+)\"\\]")
        private val ATTR_LANGUAGE = Regex("LANGUAGE=\"([^\"]*)\"")
        private val ATTR_URI = Regex("URI=\"([^\"]*)\"")
    }
}
