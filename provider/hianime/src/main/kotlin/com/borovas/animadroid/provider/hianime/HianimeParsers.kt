package com.borovas.animadroid.provider.hianime

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.SkipInterval
import com.borovas.animadroid.core.model.SubtitleTrack
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/** Pure parsing helpers for hianime (ports of ani-cli v5 shell functions). */
internal object HianimeParsers {
    const val EMBED_KEY = "otaku-embed-v1"
    private val json = Json { ignoreUnknownKeys = true }

    /** ani-cli `b64_decode`; tolerant of missing padding and url-safe alphabet. */
    fun b64Decode(input: String): ByteArray {
        val s = input.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        return Base64.getDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '='))
    }

    /** ani-cli `deobfuscate_blob`: base64 then XOR with the repeating key. Symmetric, so also encodes. */
    fun xor(data: ByteArray, key: String = EMBED_KEY): ByteArray {
        val k = key.toByteArray(Charsets.US_ASCII)
        return ByteArray(data.size) { (data[it].toInt() xor k[it % k.size].toInt()).toByte() }
    }

    fun deobfuscateBlob(blob: String): String = String(xor(b64Decode(blob)), Charsets.UTF_8)

    /**
     * Endpoints answer `{"status":true,"html":"..."}`. Returns the decoded html, or the raw text with
     * backslash escapes stripped (ani-cli behaviour) when it is not JSON.
     */
    fun htmlPayload(body: String): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) {
            val html = runCatching { json.parseToJsonElement(trimmed).jsonObject["html"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (html != null) return html
        }
        return body.replace("\\", "")
    }

    fun parseSearch(html: String, providerId: String): List<Anime> {
        val doc = Jsoup.parse(html)
        doc.select("#main-sidebar").remove()
        val seen = HashSet<String>()
        return doc.select("div.film-detail").mapNotNull { detail ->
            val a = detail.selectFirst("h3.film-name a") ?: return@mapNotNull null
            val id = a.attr("href").substringBefore('?').trimEnd('/').substringAfterLast('/')
            val title = a.attr("title").ifBlank { a.text() }
            if (id.isBlank() || title.isBlank() || !seen.add(id)) return@mapNotNull null
            val item = detail.parent()
            val poster = item?.selectFirst(".film-poster img")
            val cover = poster?.let { img ->
                listOf(img.attr("data-src"), img.attr("src")).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
            Anime(
                id = id,
                providerId = providerId,
                title = title,
                coverUrl = cover,
                subEpisodes = item?.tickCount(".tick-sub"),
                dubEpisodes = item?.tickCount(".tick-dub"),
            )
        }
    }

    private fun org.jsoup.nodes.Element.tickCount(selector: String): Int? =
        selectFirst(selector)?.text()?.filter { it.isDigit() }?.toIntOrNull()

    fun parseEpisodes(payload: String, slug: String): List<Episode> {
        val doc = Jsoup.parse(htmlPayload(payload))
        return doc.select(".ep-item").mapNotNull { el ->
            val number = el.attr("data-number")
            val id = el.attr("data-id")
            if (number.isBlank() || id.isBlank() || !el.attr("href").contains("/watch/$slug?ep=")) return@mapNotNull null
            Episode(id = id, number = number, title = el.attr("title").takeIf { it.isNotBlank() })
        }.distinctBy { it.id }.sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    /** data-hash of the ZokoAnime server for [type] ("sub"/"dub"), decoded to the embed URL. */
    fun parseEmbedUrl(payload: String, type: String): String? {
        val doc = Jsoup.parse(htmlPayload(payload))
        val hash = doc.select(".server-item[data-type=$type]")
            .firstOrNull { it.attr("data-server-name") == "ZokoAnime" }?.attr("data-hash")
        if (hash.isNullOrBlank()) return null
        return runCatching { String(b64Decode(hash), Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun embedOrigin(embedUrl: String): String =
        Regex("^(https?://[^/]*)").find(embedUrl)?.groupValues?.get(1)?.plus("/") ?: embedUrl

    fun malId(embedUrl: String): Int? = Regex("/mal/(\\d+)/").find(embedUrl)?.groupValues?.get(1)?.toIntOrNull()

    fun extractBlob(embedHtml: String): String? = Regex("window\\.__P=\"([^\"]*)\"").find(embedHtml)?.groupValues?.get(1)

    class EmbedConfig(
        val masterUrl: String?,
        val subtitles: List<SubtitleTrack>,
        val intro: SkipInterval? = null,
        val outro: SkipInterval? = null,
    )

    fun parseEmbedConfig(jsonText: String): EmbedConfig {
        val obj = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return EmbedConfig(null, emptyList())
        val src = obj["src"]?.jsonPrimitive?.contentOrNull?.takeIf { it.contains(".m3u8") }
        val subs = (obj["subtitles"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["src"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SubtitleTrack(
                url = url,
                language = o["lang"]?.jsonPrimitive?.contentOrNull,
                label = o["label"]?.jsonPrimitive?.contentOrNull,
                isDefault = o["default"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.sortedByDescending { it.isDefault }
        val skip = obj["skip"] as? JsonObject
        return EmbedConfig(src, subs, skipInterval(skip?.get("intro")), skipInterval(skip?.get("outro")))
    }

    private fun skipInterval(el: kotlinx.serialization.json.JsonElement?): SkipInterval? {
        val o = el as? JsonObject ?: return null
        val start = o["start"]?.jsonPrimitive?.doubleOrNull ?: return null
        val end = o["end"]?.jsonPrimitive?.doubleOrNull ?: return null
        return if (end > start && start >= 0) SkipInterval(start, end) else null
    }

    class MasterPlaylist(val variants: List<StreamVariant>, val externalAudioUrl: String?)

    fun parseMaster(text: String, masterUrl: String): MasterPlaylist {
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<StreamVariant>()
        var audio: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) variants += StreamVariant(height, resolve(masterUrl, lines[j]))
                i = j
            } else if (audio == null && line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") && line.contains("DEFAULT=YES")) {
                audio = Regex("URI=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.let { resolve(masterUrl, it) }
            }
            i++
        }
        return MasterPlaylist(variants.sortedByDescending { it.quality ?: -1 }, audio)
    }

    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref).toString()

    /** Synopsis and cover from an anime page. */
    fun parseDetails(html: String): Pair<String?, String?> {
        val doc = Jsoup.parse(html)
        val synopsis = doc.selectFirst(".film-description .text")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
        val cover = doc.selectFirst(".anisc-poster img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        return synopsis to cover
    }
}
