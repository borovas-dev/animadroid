package dev.borovas.animadroid.provider.hianime

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.core.model.StreamSet
import dev.borovas.animadroid.core.model.TranslationMode
import dev.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import dev.borovas.animadroid.core.network.CloudflareBlockedException
import dev.borovas.animadroid.core.network.CloudflareDetector
import dev.borovas.animadroid.provider.api.AnimeProvider
import dev.borovas.animadroid.provider.api.CloudflareChallengeException
import dev.borovas.animadroid.provider.api.ProviderException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Port of ani-cli v5 hianime scraping. Construct with a client from `:core:network`. */
class HianimeProvider(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AnimeProvider {
    override val id = ID
    override val displayName = "HiAnime"

    private val base = baseUrl.trimEnd('/')

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        val url = base.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("keyword", query.trim()).build()
        val page = get(url.toString().replace("%20", "+"))
        if (page.contains("<title>Just a moment", ignoreCase = true)) throw CloudflareChallengeException(url.toString())
        return HianimeParsers.parseSearch(page, ID)
    }

    override suspend fun details(anime: Anime): Anime {
        for (path in listOf("/${anime.id}", "/watch/${anime.id}")) {
            val html = runCatching { get(base + path) }.getOrNull() ?: continue
            val (synopsis, cover) = runCatching { HianimeParsers.parseDetails(html) }.getOrNull() ?: continue
            if (synopsis != null || cover != null) {
                return anime.copy(synopsis = anime.synopsis ?: synopsis, coverUrl = anime.coverUrl ?: cover)
            }
        }
        return anime
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        val numeric = anime.id.substringAfterLast('-')
        if (numeric.isEmpty() || !numeric.all { it.isDigit() }) {
            throw ProviderException("Unsupported HiAnime id '${anime.id}' (expected <slug>-<number>)")
        }
        val body = get("$base/api/theme/episode/list/$numeric")
        return HianimeParsers.parseEpisodes(body, anime.id)
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        val type = if (mode == TranslationMode.DUB) "dub" else "sub"
        val servers = get("$base/api/theme/episode/servers?episodeId=${episode.id}")
        val embed = HianimeParsers.parseEmbedUrl(servers, type)
            ?: throw ProviderException("No ZokoAnime $type source for episode ${episode.number}")
        val refr = HianimeParsers.embedOrigin(embed)
        val malId = HianimeParsers.malId(embed)

        val blob = HianimeParsers.extractBlob(get(embed, referer = "$base/"))
            ?: throw ProviderException("Embed page has no stream config (window.__P)")
        val config = try {
            HianimeParsers.parseEmbedConfig(HianimeParsers.deobfuscateBlob(blob))
        } catch (e: IllegalArgumentException) {
            throw ProviderException("Could not decode embed stream config", e)
        }
        val master = config.masterUrl ?: throw ProviderException("No m3u8 source in embed config")

        val playlist = get(master, referer = refr)
        val parsed = HianimeParsers.parseMaster(playlist, master)
        if (parsed.variants.isEmpty()) throw ProviderException("Master playlist has no variants")
        return StreamSet(
            masterUrl = master,
            variants = parsed.variants,
            headers = mapOf("Referer" to refr, "User-Agent" to ANI_CLI_USER_AGENT),
            externalAudioUrl = parsed.externalAudioUrl,
            subtitles = config.subtitles,
            malId = malId,
        )
    }

    private suspend fun get(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", ANI_CLI_USER_AGENT)
            .apply { referer?.let { header("Referer", it) } }.build()
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

    companion object {
        const val ID = "hianime"
        const val DEFAULT_BASE_URL = "https://hianime.at"
    }
}
