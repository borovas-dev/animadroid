package dev.borovas.animadroid.feature.recommend

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.LibraryRepository
import dev.borovas.animadroid.core.model.SettingsRepository
import dev.borovas.animadroid.provider.api.AnimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Text-in/text-out LLM. Real implementation: [LiteRtLlmEngine]; tests use a fake. */
interface LlmEngine {
    /** Loads the model lazily; throws if the model is missing or fails to load on every backend. */
    suspend fun generate(system: String, prompt: String): String

    /** Frees native memory. Safe to call at any time; the next [generate] reloads. */
    fun release()
}

interface RecommendationCache {
    val cached: Flow<CachedRecommendations?>
    suspend fun save(value: CachedRecommendations?)
}

class NoHistoryException : Exception("Watch or favorite something first so there is something to base picks on.")
class NoResultsException : Exception("The model did not suggest anything that can be watched right now. Try refreshing.")

@Singleton
class RecommendationService(
    private val engine: LlmEngine,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
    private val cache: RecommendationCache,
    private val clock: Clock,
) {
    @Inject constructor(
        engine: LlmEngine,
        library: LibraryRepository,
        settings: SettingsRepository,
        providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
        cache: RecommendationCache,
    ) : this(engine, library, settings, providers, cache, Clock { System.currentTimeMillis() })

    fun interface Clock { fun now(): Long }

    val cached: Flow<CachedRecommendations?> get() = cache.cached

    suspend fun snapshot(): HistorySnapshot = HistorySnapshot(library.continueWatching().first(), library.favorites().first())

    /** True when there is no cached list, or the history changed meaningfully / the cache is a week old. */
    fun isStale(cached: CachedRecommendations?, snapshot: HistorySnapshot): Boolean =
        cached == null ||
            HistorySnapshot.isMeaningfullyChanged(cached.signature, snapshot.signature) ||
            clock.now() - cached.generatedAt > MAX_AGE_MS

    suspend fun generate(): CachedRecommendations {
        val snap = snapshot()
        if (snap.isEmpty) throw NoHistoryException()
        val app = settings.settings.first()
        val provider = providers[app.providerId] ?: providers.values.firstOrNull() ?: error("No anime provider is available.")

        val raw = engine.generate(PromptBuilder.SYSTEM, PromptBuilder.user(snap.watched, snap.favorites))
        val suggestions = SuggestionParser.parse(raw)
        val items = resolve(suggestions, snap, provider, app.mode)
        if (items.isEmpty()) throw NoResultsException()
        return CachedRecommendations(items, snap.signature, clock.now()).also { cache.save(it) }
    }

    suspend fun clearCache() = cache.save(null)

    internal suspend fun resolve(
        suggestions: List<RawSuggestion>,
        snap: HistorySnapshot,
        provider: AnimeProvider,
        mode: dev.borovas.animadroid.core.model.TranslationMode,
    ): List<Recommendation> = withContext(Dispatchers.IO) {
        val gate = Semaphore(3)
        val excludedIds = snap.excludedIds()
        val excludedTitles = snap.excludedTitles()
        val found = coroutineScope {
            suggestions.map { s ->
                async {
                    val hit = gate.withPermit { runCatching { provider.search(s.title, mode) }.getOrNull() }
                        ?.let { bestMatch(s.title, it) }
                    hit?.let { Recommendation(it, s.reason) }
                }
            }.awaitAll().filterNotNull()
        }
        val seen = mutableSetOf<String>()
        found.filter { r ->
            val key = "${r.anime.providerId}:${r.anime.id}"
            r.anime.coverUrl != null && key !in excludedIds && normalizeTitle(r.anime.title) !in excludedTitles && seen.add(key)
        }
    }

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Picks the result whose title matches the suggestion best; requires real overlap so we never show a random show. */
        fun bestMatch(query: String, results: List<Anime>): Anime? {
            val q = normalizeTitle(query)
            if (q.isEmpty()) return null
            val qTokens = q.split(' ').toSet()
            return results.filter { it.coverUrl != null }.map { a ->
                val t = normalizeTitle(a.title)
                val tTokens = t.split(' ').toSet()
                val score = when {
                    t == q -> 1.0
                    t.contains(q) || q.contains(t) -> 0.85
                    else -> qTokens.intersect(tTokens).size.toDouble() / qTokens.union(tTokens).size
                }
                a to score
            }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
        }
    }
}
