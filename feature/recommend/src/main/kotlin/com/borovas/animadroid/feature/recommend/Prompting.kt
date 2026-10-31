package dev.borovas.animadroid.feature.recommend

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.WatchEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val RecommendJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/** A model suggestion before it is resolved against a provider. */
@Serializable
data class RawSuggestion(val title: String, val reason: String = "")

/** A resolved, watchable recommendation. */
@Serializable
data class Recommendation(val anime: Anime, val reason: String)

@Serializable
data class CachedRecommendations(
    val items: List<Recommendation>,
    /** Signature of the history the list was generated from (see [HistorySnapshot.signature]). */
    val signature: List<String>,
    val generatedAt: Long,
)

object PromptBuilder {
    const val SYSTEM = "You are an anime recommendation engine. You reply with a single JSON array and nothing else: " +
        "no markdown, no commentary."
    const val MAX_WATCHED = 25
    const val MAX_FAVORITES = 15
    const val COUNT = 8

    /** [watched] is expected most-recent-first (as returned by `continueWatching()`). */
    fun user(watched: List<WatchEntry>, favorites: List<Anime>, count: Int = COUNT): String = buildString {
        appendLine("Here is what the user watches, most recent first.")
        appendLine()
        appendLine("Watched (with how far they got):")
        watched.take(MAX_WATCHED).forEach { e ->
            val pct = (e.last.fraction * 100).toInt()
            val state = if (e.last.isFinished) "finished episode ${e.last.episodeNumber}" else "stopped at $pct% of episode ${e.last.episodeNumber}"
            appendLine("- ${clean(e.anime.title)} ($state)")
        }
        if (watched.isEmpty()) appendLine("- (nothing yet)")
        val favTitles = favorites.take(MAX_FAVORITES)
        if (favTitles.isNotEmpty()) {
            appendLine()
            appendLine("Favorites (strong positive signal):")
            favTitles.forEach { appendLine("- ${clean(it.title)}") }
        }
        appendLine()
        appendLine("Shows finished or favorited reflect taste; shows abandoned early are a weak negative signal.")
        appendLine("Recommend $count different anime they have NOT listed above, ordered best first.")
        appendLine("Use the common English or romaji title. Give a one-sentence reason (max 20 words) tied to their taste.")
        append("Answer ONLY with a JSON array in this exact shape: [{\"title\": \"...\", \"reason\": \"...\"}]")
    }

    private fun clean(t: String) = t.replace('\n', ' ').replace('"', '\'').trim().take(70)
}

object SuggestionParser {
    /** Tolerates code fences, leading/trailing prose, trailing commas, and truncated output (salvages complete objects). */
    fun parse(raw: String): List<RawSuggestion> {
        val text = raw.replace("```json", "```").replace("```JSON", "```")
            .split("```").let { parts -> parts.firstOrNull { it.contains('[') && it.contains('{') } ?: raw }
        val fromArray = parseArray(text)
        val result = fromArray.ifEmpty { salvageObjects(text) }
        return result.map { it.copy(title = it.title.trim(), reason = it.reason.trim()) }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.title.lowercase() }
    }

    private fun parseArray(text: String): List<RawSuggestion> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val candidate = text.substring(start, end + 1).replace(Regex(",\\s*([\\]}])"), "$1")
        return runCatching {
            (RecommendJson.parseToJsonElement(candidate) as? JsonArray).orEmpty().mapNotNull(::toSuggestion)
        }.getOrDefault(emptyList())
    }

    /** Finds every balanced {...} object (string-aware), for output that is cut off or not a valid array. */
    private fun salvageObjects(text: String): List<RawSuggestion> {
        val out = mutableListOf<RawSuggestion>()
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for ((i, c) in text.withIndex()) {
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        runCatching { RecommendJson.parseToJsonElement(text.substring(start, i + 1)) }.getOrNull()
                            ?.let(::toSuggestion)?.let(out::add)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun toSuggestion(e: kotlinx.serialization.json.JsonElement): RawSuggestion? {
        val o = e as? JsonObject ?: return null
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull
            ?: (o["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull ?: o["why"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return RawSuggestion(title, reason.orEmpty())
    }
}

/** What the recommender knows about the user, reduced to what matters for cache invalidation. */
data class HistorySnapshot(val watched: List<WatchEntry>, val favorites: List<Anime>) {
    val isEmpty get() = watched.isEmpty() && favorites.isEmpty()

    /** Shows that carry taste signal: favorited, finished, or at least started past 25%. */
    val signature: List<String> = buildSet {
        favorites.forEach { add("${it.providerId}:${it.id}") }
        watched.filter { it.last.isFinished || it.last.fraction >= 0.25f }.forEach { add("${it.anime.providerId}:${it.anime.id}") }
    }.sorted()

    fun excludedIds(): Set<String> = (watched.map { it.anime } + favorites).map { "${it.providerId}:${it.id}" }.toSet()
    fun excludedTitles(): Set<String> = (watched.map { it.anime } + favorites).map { normalizeTitle(it.title) }.toSet()

    companion object {
        /** Regenerate when at least this many signal shows were added or removed since the cached list. */
        const val MEANINGFUL_CHANGE = 2

        fun isMeaningfullyChanged(old: List<String>, new: List<String>): Boolean {
            val o = old.toSet(); val n = new.toSet()
            return (o - n).size + (n - o).size >= MEANINGFUL_CHANGE
        }
    }
}

internal fun normalizeTitle(t: String): String =
    t.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
