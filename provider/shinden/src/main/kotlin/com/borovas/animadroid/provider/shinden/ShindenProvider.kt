package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.ProviderException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Polish provider for shinden.pl (port of alt-ani-cli-win-shinden, using the in-app Cloudflare solver instead of FlareSolverr).
 *
 * Mode mapping: [TranslationMode.DUB] = Polish audio (lektor/dubbing); [TranslationMode.SUB] = original audio with
 * Polish (then English) subtitles. Only the public guest token documented by the reference tool is used; no account.
 */
class ShindenProvider(
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    apiBaseUrl: String = DEFAULT_API_URL,
    /** Server-enforced pause between `player_load` and `player_show`; shorter values make `player_show` come back empty. */
    private val antibotDelayMs: Long = 5_000,
    private val maxResolveAttempts: Int = 3,
) : AnimeProvider {
    override val id = ID
    override val displayName = "Shinden (PL)"

    private val base = baseUrl.trimEnd('/')
    private val api = apiBaseUrl.trimEnd('/')
    private val http = ShindenHttp(client)
    private val kinds = ConcurrentHashMap<String, String>()
    private val mal = ConcurrentHashMap<String, Int>()

    /** Host lookup; overridable in tests so a mock server can stand in for a real video host. */
    internal var hostResolver: (String) -> HostExtractor? = HostRegistry::byHost

    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        val url = base.toHttpUrl().newBuilder().addPathSegment("series").addQueryParameter("search", query.trim()).build().toString()
        return ShindenParsers.parseSearch(http.get(url, referer = "$base/"), ID, base)
    }

    override suspend fun details(anime: Anime): Anime {
        val (kind, html) = runCatching { seriesPage(anime.id, "") }.getOrNull() ?: return anime
        kinds[anime.id] = kind
        val d = ShindenParsers.parseDetails(html)
        d.malId?.let { mal[anime.id] = it }
        return anime.copy(
            synopsis = anime.synopsis ?: d.synopsis,
            coverUrl = anime.coverUrl ?: ShindenParsers.absolute(base, d.cover),
            malId = anime.malId ?: d.malId,
        )
    }

    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        val (kind, first) = seriesPage(anime.id, "/episodes?na_stronie=100")
        kinds[anime.id] = kind
        val rows = ShindenParsers.parseEpisodeRows(first).toMutableList()
        for (page in 2..minOf(ShindenParsers.pageCount(first), MAX_PAGES)) {
            rows += ShindenParsers.parseEpisodeRows(http.get("$base/$kind/${anime.id}/episodes?na_stronie=100&strona=$page", "$base/"))
        }
        // The site lists newest first; the app wants ascending.
        val episodes = rows.distinctBy { it.id }.reversed()
        if (episodes.isEmpty()) throw ProviderException("Shinden lists no episodes with players for '${anime.title}'")
        return episodes
    }

    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        val page = http.get("$base/episode/${anime.id}/view/${episode.id}", referer = "$base/")
        val players = ShindenParsers.parsePlayers(page)
        val candidates = ShindenSelection.rank(players, mode)
        if (candidates.isEmpty()) {
            val have = players.map { ShindenSelection.label(it) }.distinct().joinToString().ifEmpty { "none" }
            throw ProviderException(
                (if (mode == TranslationMode.DUB) "No Polish dub/lektor players" else "No subtitled players") +
                    " for episode ${episode.number} (available: $have)",
            )
        }
        val skipped = mutableListOf<String>()
        var attempts = 0
        for (p in candidates) {
            HostRegistry.externalReason(p.player)?.let { skipped += "${p.player}: $it"; continue }
            if (attempts >= maxResolveAttempts) { skipped += "${p.player}: not tried (attempt limit)"; continue }
            attempts++
            val show = try {
                resolveShow(p.onlineId)
            } catch (e: CloudflareChallengeException) {
                throw e
            } catch (e: ProviderException) {
                skipped += "${p.player}: ${e.message}"; continue
            }
            when (show) {
                is PlayerShow.External -> skipped += "${p.player}: external link to ${show.label ?: show.url}"
                is PlayerShow.Failure -> skipped += "${p.player}: ${show.message}"
                is PlayerShow.Embed -> {
                    val host = runCatching { java.net.URI(show.url).host.orEmpty() }.getOrDefault("")
                    // Some hosts rotate domains (Vidara), so fall back to shinden's own player label.
                    val extractor = hostResolver(host) ?: HostRegistry.byPlayerName(p.player)
                    if (extractor == null) {
                        skipped += "${p.player}: unsupported host '$host'"
                    } else {
                        try {
                            val r = extractor.extract(show.url, http)
                            return StreamSet(
                                masterUrl = if (r.hls) r.url else null,
                                variants = if (r.hls) emptyList() else r.variants.ifEmpty { listOf(com.borovas.animadroid.core.model.StreamVariant(p.res, r.url)) },
                                headers = r.headers,
                                malId = anime.malId ?: mal[anime.id],
                                languageLabel = ShindenSelection.label(p),
                            )
                        } catch (e: CloudflareChallengeException) {
                            throw e
                        } catch (e: ProviderException) {
                            skipped += "${p.player}: ${e.message}"
                        }
                    }
                }
            }
        }
        throw ProviderException("No playable Shinden source for episode ${episode.number}: ${skipped.joinToString("; ")}")
    }

    /** `player_load` then, after the antibot pause, `player_show`; mirrors the reference tool's two-step protocol. */
    private suspend fun resolveShow(onlineId: String): PlayerShow {
        val auth = "auth=$GUEST_AUTH"
        http.get("$api/xhr/$onlineId/player_load?$auth", referer = "$base/")
        delay(antibotDelayMs)
        return ShindenParsers.parsePlayerShow(http.get("$api/xhr/$onlineId/player_show?$auth&width=0&height=-1", referer = "$base/"))
    }

    /** Fetches `/<kind>/<id><suffix>`, trying `series` then `titles` (the site uses both prefixes for anime). */
    private suspend fun seriesPage(idSlug: String, suffix: String): Pair<String, String> {
        if (!ShindenParsers.isValidId(idSlug)) throw ProviderException("Unsupported Shinden id '$idSlug'")
        val order = (kinds[idSlug]?.let { listOf(it) }.orEmpty() + listOf("series", "titles")).distinct()
        var last: HttpStatusException? = null
        for (kind in order) {
            try {
                return kind to http.getChecked("$base/$kind/$idSlug$suffix", referer = "$base/")
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw ProviderException(e.message ?: "HTTP ${e.code}", e)
                last = e
            }
        }
        throw ProviderException("Shinden page not found for '$idSlug' (HTTP ${last?.code ?: 404})", last)
    }

    companion object {
        const val ID = "shinden"
        const val DEFAULT_BASE_URL = "https://shinden.pl"
        const val DEFAULT_API_URL = "https://api4.shinden.pl"

        /** Public guest token (base64 of `_guest_:0,5,21000000,255,4174293644`, URL-encoded) documented by the reference tool. */
        const val GUEST_AUTH = "X2d1ZXN0XzowLDUsMjEwMDAwMDAsMjU1LDQxNzQyOTM2NDQ%3D"
        private const val MAX_PAGES = 10
    }
}

/** Pure ranking and labelling of shinden player entries. */
internal object ShindenSelection {
    /** Entries usable for [mode], best first. DUB = Polish audio; SUB = other audio, Polish subs preferred. */
    fun rank(players: List<PlayerEntry>, mode: TranslationMode): List<PlayerEntry> {
        val filtered = players.filter { (it.audio == "pl") == (mode == TranslationMode.DUB) }
        return filtered.sortedWith(
            compareByDescending<PlayerEntry> { nativeHost(it) }
                .thenByDescending { subRank(it.sub) }
                .thenByDescending { if (it.machine) 0 else 1 }
                .thenByDescending { it.res ?: 0 },
        )
    }

    /** Players with a known extractor are tried before link-out services and unknown hosts. */
    private fun nativeHost(p: PlayerEntry): Int = when {
        HostRegistry.byPlayerName(p.player) != null -> 2
        HostRegistry.externalReason(p.player) == null -> 1
        else -> 0
    }

    private fun subRank(sub: String) = when (sub) { "pl" -> 3; "en" -> 2; "" -> 0; else -> 1 }

    fun label(p: PlayerEntry): String {
        val audio = when (p.audio) {
            "pl" -> "Polish dub/lektor"
            "jp" -> "JP audio"
            "en" -> "EN audio"
            "" -> "audio ?"
            else -> "${p.audio.uppercase()} audio"
        }
        val subs = when (p.sub) { "" -> null; "pl" -> "PL subs"; "en" -> "EN subs"; else -> "${p.sub.uppercase()} subs" }
        return listOfNotNull(audio, subs).joinToString(" + ")
    }
}
