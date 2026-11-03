package com.borovas.animadroid.provider.animefire

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.ProviderException
import com.borovas.animadroid.core.remoteconfig.ProviderRemoteConfig
import com.borovas.animadroid.provider.common.HostHttp
import com.borovas.animadroid.provider.common.withMirrors
import com.borovas.animadroid.provider.common.M3u8
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * AnimeFire (pt-BR). Mirrors the Aniyomi extension: the site is a SPA over a JSON API (`api.animefire.one`, `?v=3`),
 * episodes resolve to HLS master playlists on its own CDN, tagged `legendado` (hardsub pt-BR) or `dublado`.
 * Site base URL(s) come from [config] per request (mirrors tried on connection failure/5xx); `params.apiUrl` overrides the API host.
 */
class AnimefireProvider(
    client: OkHttpClient,
    private val config: () -> ProviderRemoteConfig = { ProviderRemoteConfig.DEFAULT },
) : AnimeProvider {
    override val id = ID
    override val displayName = "AnimeFire"
    override val contentLanguages = setOf("pt")
    override val gfwBlocked = false
    override val priority = 0

    private fun bases() = config().mirrorsOr(listOf(DEFAULT_BASE_URL))
    private fun api() = config().param("apiUrl", DEFAULT_API_URL).trimEnd('/')
    private val http = HostHttp(client, acceptLanguage = "pt-BR,pt;q=0.9,en;q=0.7")

    private fun endpoint(vararg segments: String, query: Map<String, String> = emptyMap()): String {
        val b = api().toHttpUrl().newBuilder()
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.addQueryParameter("v", "3").build().toString()
    }

    private suspend fun getApi(base: String, url: String) = http.get(url, referer = "$base/")

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return withMirrors(bases()) { base -> AnimefireParsers.parseSearch(getApi(base, endpoint("animes", "pesquisar", query = mapOf("q" to q, "page" to "1"))), ID) }
    }

    override suspend fun details(anime: Anime): Anime {
        val d = runCatching { withMirrors(bases()) { base -> AnimefireParsers.parseDetails(getApi(base, endpoint("anime", anime.id)), ID) } }.getOrNull() ?: return anime
        return anime.copy(synopsis = anime.synopsis ?: d.synopsis, coverUrl = anime.coverUrl ?: d.coverUrl)
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> =
        withMirrors(bases()) { base -> AnimefireParsers.parseEpisodes(getApi(base, endpoint("anime", anime.id))) }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet =
        withMirrors(bases()) { streamsAt(it, episode, mode) }

    private suspend fun streamsAt(base: String, episode: Episode, mode: TranslationMode): StreamSet {
        val dub = mode == TranslationMode.DUB
        val body = getApi(base, endpoint("episode", episode.id))
        val master = AnimefireParsers.parseStreamUrl(body, dub)
            ?: throw ProviderException("No ${if (dub) "dublado" else "legendado"} source for episode ${episode.number}")
        val headers = mapOf("Referer" to "$base/", "Origin" to base, "User-Agent" to ANI_CLI_USER_AGENT)
        val parsed = M3u8.parseMaster(http.get(master, referer = "$base/"), master)
        val variants = parsed.variants.ifEmpty { listOf(com.borovas.animadroid.core.model.StreamVariant(null, master)) }
        return StreamSet(
            masterUrl = master,
            variants = variants,
            headers = headers,
            externalAudioUrl = parsed.externalAudioUrl,
            hardsubLanguage = if (dub) null else "pt",
        )
    }

    companion object {
        const val ID = "animefire"
        const val DEFAULT_BASE_URL = "https://animefire.one"
        const val DEFAULT_API_URL = "https://api.animefire.one"
    }
}
