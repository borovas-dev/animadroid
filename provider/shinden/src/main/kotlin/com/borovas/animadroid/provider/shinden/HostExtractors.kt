package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.network.ANI_CLI_USER_AGENT
import com.borovas.animadroid.provider.api.ProviderException
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/** A Media3-playable result from a video host. [hls] true means [url] is an m3u8 playlist. */
internal data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val hls: Boolean,
    val variants: List<StreamVariant> = emptyList(),
)

/** One video host. Add a new host by implementing this and listing it in [HostRegistry.all]. */
internal interface HostExtractor {
    val name: String

    /** Lowercase fragments of shinden's `player` label that identify this host without an API call. */
    val playerNameHints: List<String>

    fun handles(host: String): Boolean
    suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream
}

internal object HostRegistry {
    val all: List<HostExtractor> = listOf(SibnetExtractor, JwPlayerExtractor, CdaExtractor, VkExtractor, VidaraExtractor, YouruploadExtractor)

    /** Shinden player labels that only link out to a commercial service; they never carry an embeddable video. */
    private val EXTERNAL = listOf(
        "crunchyroll", "netflix", "disney", "amazon", "prime", "hidive", "hbo", "apple", "muse", "bilibili", "youtube",
    )
    private val UNSUPPORTED = mapOf(
        "mega" to "MEGA is end-to-end encrypted and cannot be streamed by this app",
    )

    fun byHost(host: String): HostExtractor? = all.firstOrNull { it.handles(host.lowercase()) }

    fun byPlayerName(name: String): HostExtractor? {
        val n = name.lowercase()
        return all.firstOrNull { e -> e.playerNameHints.any { n.contains(it) } }
    }

    fun externalReason(name: String): String? {
        val n = name.lowercase()
        UNSUPPORTED.entries.firstOrNull { n.contains(it.key) }?.let { return it.value }
        val tokens = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return if (EXTERNAL.any { it in tokens }) {
            "$name is an external service link, not an embeddable video"
        } else null
    }
}

private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
private fun unescapeSlashes(s: String) = s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
private fun String.heightFromLabel(): Int? = Regex("""(\d{3,4})""").find(this)?.value?.toIntOrNull()

/** video.sibnet.ru: the shell page names an mp4 path that 302s to a referer-checked CDN host. */
internal object SibnetExtractor : HostExtractor {
    override val name = "Sibnet"
    override val playerNameHints = listOf("sibnet")
    override fun handles(host: String) = host.endsWith("sibnet.ru")

    private val SRC = Regex("""player\.src\(\s*\[\s*\{\s*src\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
    private val SRC_ANY = Regex("""src\s*:\s*["'](/v/[^"']+\.mp4[^"']*)["']""")

    fun parse(html: String): String? = (SRC.find(html) ?: SRC_ANY.find(html))?.groupValues?.get(1)

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val page = http.get(embedUrl, referer = "https://shinden.pl/")
        val path = parse(page) ?: throw ProviderException("Sibnet: no mp4 source in embed page (video removed?)")
        val abs = URI(embedUrl).resolve(path).toString()
        val referer = "https://video.sibnet.ru/"
        val final = http.redirectTarget(abs, referer = embedUrl) ?: abs
        return ResolvedStream(final, mapOf("Referer" to referer, "User-Agent" to ANI_CLI_USER_AGENT), hls = false)
    }
}

/** streamwish / filemoon / playerwish and friends: JWPlayer `sources`, `hlsN` keys, optionally Dean Edwards packed. */
internal object JwPlayerExtractor : HostExtractor {
    override val name = "JWPlayer family"
    private val HOSTS = listOf(
        "streamwish", "playerwish", "hlswish", "wishembed", "awish", "dwish", "swdyu", "strwish", "sfastwish", "flaswish",
        "filemoon", "moonplayer", "kerapoxy", "vidhide", "dhtpre", "minochinos", "streamhide",
    )
    override val playerNameHints = listOf("streamwish", "filemoon", "vidhide", "playerwish", "wishembed")
    override fun handles(host: String) = HOSTS.any { host.contains(it) }

    private val SOURCES = Regex(""""?sources"?\s*:\s*\[\s*\{[^}]*"?file"?\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
    private val FILE = Regex(""""?file"?\s*:\s*["']([^"']{20,}\.(?:mp4|m3u8)[^"']*)["']""")
    private val HLS = Regex(""""hls([234]?)"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
    private val PACKER = Regex(
        """\}\s*\(\s*'((?:[^'\\]|\\.)*?)'\s*,\s*(\d+)\s*,\s*\d+\s*,\s*'([^']*)'\s*(?:\.split\('\|'\))?\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL,
    )
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun decodeBase(word: String, base: Int): Int? {
        var n = 0
        for (ch in word) {
            val idx = ALPHABET.indexOf(ch)
            if (idx < 0 || idx >= base) return null
            n = n * base + idx
        }
        return n
    }

    /** Decodes a Dean Edwards packed script and returns the page text with the decoded script appended; unchanged if none. */
    fun unpack(html: String): String {
        val m = PACKER.find(html) ?: return html
        val packed = m.groupValues[1].replace("\\'", "'")
        val base = m.groupValues[2].toInt()
        val keys = m.groupValues[3].split("|")
        val decoded = Regex("""\b\w+\b""").replace(packed) { w ->
            val n = decodeBase(w.value, base)
            if (n != null && n < keys.size && keys[n].isNotEmpty()) keys[n] else w.value
        }
        return html + "\n" + decoded
    }

    /** Returns the stream URL (resolved against [embedUrl]) or null. */
    fun parse(html: String, embedUrl: String): String? {
        val text = unpack(html)
        val raw = SOURCES.find(text)?.groupValues?.get(1)
            ?: HLS.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }.let { found ->
                listOf("4", "3", "2", "").firstNotNullOfOrNull { found[it] }
            }
            ?: FILE.find(text)?.groupValues?.get(1)
            ?: return null
        return runCatching { URI(embedUrl).resolve(raw.replace("\\/", "/")).toString() }.getOrNull()
    }

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val page = http.get(embedUrl, referer = "https://shinden.pl/")
        val url = parse(page, embedUrl) ?: throw ProviderException("${hostOf(embedUrl)}: no video URL in embed page")
        val origin = URI(embedUrl).let { "${it.scheme}://${it.host}/" }
        return ResolvedStream(
            url, mapOf("Referer" to origin, "User-Agent" to ANI_CLI_USER_AGENT), hls = url.contains(".m3u8"),
        )
    }
}

/** cda.pl / ebd.cda.pl: `player_data` JSON; HLS via `manifest_apple` (verified live), legacy scrambled `file` ported from youtube-dl. */
internal object CdaExtractor : HostExtractor {
    override val name = "CDA"
    override val playerNameHints = listOf("cda")
    override fun handles(host: String) = host == "cda.pl" || host.endsWith(".cda.pl")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ATTR = Regex("""player_data\s*=\s*(?:'(\{.*?\})'|"(\{.*?\})")""", RegexOption.DOT_MATCHES_ALL)

    fun decryptFile(input: String): String {
        var a = input
        for (p in listOf("_XDDD", "_CDA", "_ADC", "_CXD", "_QWE", "_Q5", "_IKSDE")) a = a.replace(p, "")
        a = URLDecoder.decode(a, "UTF-8")
        a = buildString {
            for (c in a) {
                val f = c.code
                append(if (f in 33..126) (33 + (f + 14) % 94).toChar() else c)
            }
        }
        a = a.replace(".cda.mp4", "").replace(".2cda.pl", ".cda.pl").replace(".3cda.pl", ".cda.pl")
        return if ("/upstream" in a) "https://" + a.replace("/upstream", ".mp4/upstream") else "https://$a.mp4"
    }

    /** A resolved CDA source: [hls] true when [url] is the apple (m3u8) manifest. */
    data class Source(val url: String, val height: Int?, val hls: Boolean)

    /**
     * Current CDA pages leave `video.file` empty and expose `manifest_apple` (HLS); older ones carry a scrambled
     * `file` (mp4). Prefer the mp4 when present, else the HLS manifest.
     */
    fun parse(html: String): Source? {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("[player_data]")?.attr("player_data")
            ?: ATTR.find(html)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.let { Jsoup.parse(it).text() }
            ?: return null
        val video: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject["video"]?.jsonObject }.getOrNull() ?: return null
        fun str(k: String) = video[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it != "None" }
        val height = str("height")?.toIntOrNull() ?: str("quality")?.heightFromLabel()
        str("file")?.let { f ->
            val url = if (f.startsWith("http")) f else decryptFile(f)
            return Source(url, height, hls = url.contains(".m3u8"))
        }
        str("manifest_apple")?.takeIf { it.startsWith("http") }?.let { return Source(it, height, hls = true) }
        return null
    }

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val page = http.get(embedUrl, referer = "https://shinden.pl/")
        val src = parse(page) ?: throw ProviderException("CDA: no playable file in player_data (video removed or protected?)")
        return ResolvedStream(
            src.url, mapOf("Referer" to "https://ebd.cda.pl/", "User-Agent" to ANI_CLI_USER_AGENT), hls = src.hls,
            variants = if (src.hls) emptyList() else listOf(StreamVariant(src.height, src.url)),
        )
    }
}

/** vk.com / vkvideo.ru `video_ext.php`: direct `urlNNN` mp4 entries and an `hls` fallback in the page JSON. */
internal object VkExtractor : HostExtractor {
    override val name = "VK"
    override val playerNameHints = listOf("vk")
    override fun handles(host: String) = host == "vk.com" || host.endsWith(".vk.com") || host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")

    private val MP4 = Regex(""""url(\d{3,4})"\s*:\s*"([^"]+)"""")
    private val HLS = Regex(""""hls"\s*:\s*"([^"]+)"""")

    /** mp4 variants best first, plus an optional HLS master. */
    fun parse(html: String): Pair<List<StreamVariant>, String?> {
        val mp4 = MP4.findAll(html).map { StreamVariant(it.groupValues[1].toInt(), unescapeSlashes(it.groupValues[2])) }
            .distinctBy { it.quality }.sortedByDescending { it.quality }.toList()
        return mp4 to HLS.find(html)?.groupValues?.get(1)?.let(::unescapeSlashes)
    }

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val page = http.get(embedUrl, referer = "https://shinden.pl/")
        val (mp4, hls) = parse(page)
        val headers = mapOf("Referer" to "https://vk.com/", "User-Agent" to ANI_CLI_USER_AGENT)
        if (mp4.isNotEmpty()) return ResolvedStream(mp4.first().url, headers, hls = false, variants = mp4)
        if (hls != null) return ResolvedStream(hls, headers, hls = true)
        throw ProviderException("VK: no playable url in video_ext page (private or removed video?)")
    }
}

/**
 * Vidara (rotating domains, e.g. ykfrtza.com; embed `/e/<filecode>`): `POST /api/stream` returns an HLS master.
 * Identified by shinden's player label, since the domain changes. Verified live against a real embed (2026-11-01).
 */
internal object VidaraExtractor : HostExtractor {
    override val name = "Vidara"
    override val playerNameHints = listOf("vidara")
    override fun handles(host: String) = false

    private val EMBED = Regex("""^(https?://[^/]+)/(?:e|d|f|v)/([A-Za-z0-9_-]+)""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseStreamingUrl(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["streaming_url"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.startsWith("http") }

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val m = EMBED.find(embedUrl) ?: throw ProviderException("Vidara: unrecognised embed url $embedUrl")
        val (origin, code) = m.destructured
        val body = http.postJson("$origin/api/stream", """{"filecode":"$code","device":"web"}""", referer = embedUrl, origin = origin)
        val url = parseStreamingUrl(body) ?: throw ProviderException("Vidara: no streaming_url (video removed?)")
        return ResolvedStream(
            url, mapOf("Referer" to embedUrl, "Origin" to origin, "User-Agent" to ANI_CLI_USER_AGENT), hls = url.contains(".m3u8"),
        )
    }
}

/** yourupload.com embed: the page's `og:video` / jwplayer `file:` is an mp4 URL that redirects to a CDN and needs the embed Referer. */
internal object YouruploadExtractor : HostExtractor {
    override val name = "YourUpload"
    override val playerNameHints = listOf("yourupload")
    override fun handles(host: String) = host.endsWith("yourupload.com")

    private val OG = Regex("""property=["']og:video["']\s+content=["']([^"']+)["']""")
    private val FILE = Regex("""file\s*:\s*['"](https?://[^'"]+\.mp4[^'"]*)['"]""")

    fun parse(html: String): String? = (OG.find(html) ?: FILE.find(html))?.groupValues?.get(1)?.replace("&amp;", "&")

    override suspend fun extract(embedUrl: String, http: ShindenHttp): ResolvedStream {
        val page = http.get(embedUrl, referer = "https://shinden.pl/")
        val url = parse(page) ?: throw ProviderException("YourUpload: no video url in embed page (video removed?)")
        return ResolvedStream(url, mapOf("Referer" to embedUrl, "User-Agent" to ANI_CLI_USER_AGENT), hls = false)
    }
}
