package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/** One entry of an episode's "Playery" list. [audio]/[sub] are shinden language codes ("jp", "pl", "en", ""). */
internal data class PlayerEntry(
    val onlineId: String,
    val player: String,
    val audio: String,
    val sub: String,
    val res: Int?,
    val translator: String?,
    val machine: Boolean,
)

/** What `player_show` returned for one online id. */
internal sealed interface PlayerShow {
    /** Embeddable iframe, to be handed to a host extractor. */
    data class Embed(val url: String) : PlayerShow

    /** Link-out only (Crunchyroll, Netflix, ...), cannot be played in-app. */
    data class External(val url: String, val label: String?) : PlayerShow

    data class Failure(val message: String) : PlayerShow
}

internal data class SeriesDetails(val synopsis: String?, val cover: String?, val malId: Int?)

internal object ShindenParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val SERIES_HREF = Regex("""/(series|titles)/(\d+-[^/?#"\s]+)""")
    private val MAL_RE = Regex("""myanimelist\.net/anime/(\d+)""")
    private val VIEW_RE = Regex("""/episode/[^/]+/view/(\d+)""")
    private val DIGITS = Regex("""\d+""")

    fun absolute(base: String, url: String?): String? {
        val u = url?.trim().orEmpty()
        return when {
            u.isEmpty() -> null
            u.startsWith("//") -> "https:$u"
            u.startsWith("http") -> u
            u.startsWith("/") -> base.trimEnd('/') + u
            else -> null
        }
    }

    /** Search result list (`ol.sh-tagi-wyniki`). Anime ids are `<number>-<slug>`; the `/series` vs `/titles` prefix is not encoded. */
    fun parseSearch(html: String, providerId: String, base: String): List<Anime> {
        val doc = Jsoup.parse(html, base)
        return doc.select("li.sh-tagi-wynik").mapNotNull { li ->
            val href = li.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val id = SERIES_HREF.find(href)?.groupValues?.get(2) ?: return@mapNotNull null
            val title = li.selectFirst(".sh-tagi-nazwa")?.text()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            val episodes = li.selectFirst("[data-episodes]")?.attr("data-episodes")?.toIntOrNull()?.takeIf { it > 0 }
            Anime(
                id = id,
                providerId = providerId,
                title = title,
                coverUrl = absolute(base, li.selectFirst("img")?.attr("src")),
                subEpisodes = episodes,
                year = li.selectFirst(".sh-tagi-rok")?.text()?.trim()?.toIntOrNull(),
            )
        }.distinctBy { it.id }
    }

    fun parseDetails(html: String): SeriesDetails {
        val doc = Jsoup.parse(html)
        val description = doc.selectFirst("#description, .title-full-description")
        val synopsis = description?.let { d ->
            d.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { d.text().trim() }
        }?.ifEmpty { null } ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifEmpty { null }
        val ldImage = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
            runCatching { findString(json.parseToJsonElement(s.data()), "image") }.getOrNull()
        }
        val cover = ldImage ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }
        val mal = MAL_RE.find(html)?.groupValues?.get(1)?.toIntOrNull()
        return SeriesDetails(synopsis, cover, mal)
    }

    private fun findString(e: JsonElement, key: String): String? = when (e) {
        is JsonObject -> (e[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: e.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    /**
     * Episode rows of one `/episodes` page, as listed by the site (newest first). Rows without a link carry no
     * players and are skipped. Episode.id is the numeric `view` id.
     */
    fun parseEpisodeRows(html: String): List<Episode> =
        Jsoup.parse(html).select("li.ep-row").mapNotNull { row ->
            val href = row.selectFirst("a.ep-row__link[href]")?.attr("href") ?: return@mapNotNull null
            val id = VIEW_RE.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val number = row.selectFirst(".ep-row__no")?.text()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            Episode(id = id, number = number, title = row.selectFirst(".ep-row__title")?.text()?.trim()?.ifEmpty { null })
        }

    /** Highest page number linked from the pagination (`?strona=N`), 1 when there is no pagination. */
    fun pageCount(html: String): Int =
        Jsoup.parse(html).select("nav.ep-pages a[href]").mapNotNull { a ->
            Regex("""[?&]strona=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 1

    /** Players of an episode page: `#episode-players-data` JSON (current site) or `a[data-episode]` buttons (legacy layout). */
    fun parsePlayers(html: String): List<PlayerEntry> {
        val doc = Jsoup.parse(html)
        doc.selectFirst("script#episode-players-data")?.let { script ->
            val arr = runCatching { json.parseToJsonElement(script.data()).jsonObject["players"]?.jsonArray }.getOrNull()
            if (arr != null) {
                return arr.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.mapNotNull { o ->
                    val id = o.str("online_id") ?: return@mapNotNull null
                    val name = o.str("player") ?: return@mapNotNull null
                    PlayerEntry(
                        id, name, o.str("audio").orEmpty().lowercase(), o.str("sub").orEmpty().lowercase(),
                        o.str("res")?.let { DIGITS.find(it)?.value?.toIntOrNull() },
                        o.str("translator"), (o["machine"] as? JsonPrimitive)?.booleanOrNull == true,
                    )
                }
            }
        }
        return doc.select("a[data-episode]").mapNotNull { a ->
            val o = runCatching { json.parseToJsonElement(a.attr("data-episode")).jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o.str("online_id") ?: return@mapNotNull null
            val name = o.str("player") ?: return@mapNotNull null
            PlayerEntry(
                id, name, o.str("lang_audio").orEmpty().lowercase(), o.str("lang_subs").orEmpty().lowercase(),
                o.str("max_res")?.let { DIGITS.find(it)?.value?.toIntOrNull() }, o.str("subs_author"), false,
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun parsePlayerShow(html: String): PlayerShow {
        val doc = Jsoup.parse(html)
        doc.selectFirst("iframe[src]")?.attr("src")?.let { src ->
            val url = absolute("https://shinden.pl", src) ?: return PlayerShow.Failure("Unexpected iframe src '$src'")
            return PlayerShow.Embed(url)
        }
        doc.selectFirst("a.button-player[href]")?.let { a ->
            return PlayerShow.External(a.attr("href"), a.text().trim().ifEmpty { null })
        }
        val msg = doc.selectFirst(".player-message, .error")?.text()?.trim()
        return PlayerShow.Failure(msg ?: "player_show returned no player (antibot delay too short?): ${html.trim().take(120)}")
    }

    fun isValidId(idSlug: String): Boolean = Regex("""\d+-[A-Za-z0-9._~-]+""").matches(idSlug)
}
