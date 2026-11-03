package com.borovas.animadroid.provider.hianime

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.core.network.CloudflareBlockedException
import com.borovas.animadroid.core.network.CloudflareDetector
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.ProviderException
import com.borovas.animadroid.provider.common.HttpStatusException
import com.borovas.animadroid.provider.common.M3u8
import com.borovas.animadroid.provider.common.withMirrors
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Port of ani-cli v5 hianime scraping. Base URL(s), server name and XOR key come from [config] on every request. */
class HianimeProvider(
    private val client: OkHttpClient,
    private val config: () -> ProviderRemoteConfig = { ProviderRemoteConfig.DEFAULT },
) : AnimeProvider {
    override val id = ID
    override val displayName = "HiAnime"
    override val contentLanguages = setOf("en")
    override val gfwBlocked = false // hianime.at is reachable per greatfire.org (the older hianime.to/.com domains were blocked)
    override val reportsDubAvailability = true
    override val priority = 0

    private fun bases() = config().mirrorsOr(listOf(DEFAULT_BASE_URL))

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> = withMirrors(bases()) { base ->
        val url = base.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("keyword", query.trim()).build()
        val page = get(url.toString().replace("%20", "+"))
        if (page.contains("<title>Just a moment", ignoreCase = true)) throw CloudflareChallengeException(url.toString())
        HianimeParsers.parseSearch(page, ID)
    }

    override suspend fun details(anime: Anime): Anime {
        val base = bases().first()
        for (path in listOf("/${anime.id}", "/watch/${anime.id}")) {
            val html = runCatching { get(base + path) }.getOrNull() ?: continue
            val (synopsis, cover) = runCatching { HianimeParsers.parseDetails(html) }.getOrNull() ?: continue
            if (synopsis != null || cover != null) {
                return anime.copy(synopsis = anime.synopsis ?: synopsis, coverUrl = anime.coverUrl ?: cover)
            }
        }
        return anime
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> = withMirrors(bases()) { base ->
        val numeric = anime.id.substringAfterLast('-')
        if (numeric.isEmpty() || !numeric.all { it.isDigit() }) {
            throw ProviderException("Unsupported HiAnime id '${anime.id}' (expected <slug>-<number>)")
        }
        val body = get("$base/api/theme/episode/list/$numeric")
        val all = HianimeParsers.parseEpisodes(body, anime.id)
        // The site lists every (sub) episode; dub usually lags behind, so cap to the dub count when known.
        val dubCount = anime.dubEpisodes
        if (mode == TranslationMode.DUB && dubCount != null && dubCount > 0) all.take(dubCount) else all
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet = withMirrors(bases()) { base ->
        val cfg = config()
        val serverName = cfg.param("serverName", HianimeParsers.SERVER_NAME)
        val xorKey = cfg.param("xorKey", HianimeParsers.EMBED_KEY)
        val type = if (mode == TranslationMode.DUB) "dub" else "sub"
        val servers = get("$base/api/theme/episode/servers?episodeId=${episode.id}")
        val embed = HianimeParsers.parseEmbedUrl(servers, type, serverName)
            ?: throw ProviderException("No $serverName $type source for episode ${episode.number}")
        val refr = HianimeParsers.embedOrigin(embed)
        val malId = HianimeParsers.malId(embed)

        val blob = HianimeParsers.extractBlob(get(embed, referer = "$base/"))
            ?: throw ProviderException("Embed page has no stream config (window.__P)")
        val config = try {
            HianimeParsers.parseEmbedConfig(HianimeParsers.deobfuscateBlob(blob, xorKey))
        } catch (e: IllegalArgumentException) {
            throw ProviderException("Could not decode embed stream config", e)
        }
        val master = config.masterUrl ?: throw ProviderException("No m3u8 source in embed config")

        val playlist = get(master, referer = refr)
        val parsed = M3u8.parseMaster(playlist, master)
        if (parsed.variants.isEmpty()) throw ProviderException("Master playlist has no variants")
        StreamSet(
            masterUrl = master,
            variants = parsed.variants,
            headers = mapOf("Referer" to refr, "User-Agent" to ANI_CLI_USER_AGENT),
            externalAudioUrl = parsed.externalAudioUrl,
            subtitles = config.subtitles,
            malId = malId,
            intro = config.intro,
            outro = config.outro,
            spriteVttUrl = config.spriteVttUrl,
        )
    }

    private suspend fun get(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", ANI_CLI_USER_AGENT)
            .apply { referer?.let { header("Referer", it) } }.build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (CloudflareDetector.isChallenge(resp.code, resp.headers) { text }) throw CloudflareChallengeException(url)
                if (!resp.isSuccessful) {
                    val status = HttpStatusException(resp.code, url)
                    throw ProviderException(status.message ?: "HTTP ${resp.code}", status)
                }
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
