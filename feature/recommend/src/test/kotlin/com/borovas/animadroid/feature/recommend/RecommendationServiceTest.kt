package com.borovas.animadroid.feature.recommend

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.EpisodeProgress
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.core.model.WatchEntry
import com.borovas.animadroid.provider.api.AnimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeEngine(var reply: String) : LlmEngine {
    var calls = 0; var lastPrompt = ""; var released = 0
    override suspend fun generate(system: String, prompt: String): String { calls++; lastPrompt = prompt; return reply }
    override fun release() { released++ }
}

private class FakeCache : RecommendationCache {
    val state = MutableStateFlow<CachedRecommendations?>(null)
    override val cached: Flow<CachedRecommendations?> get() = state
    override suspend fun save(value: CachedRecommendations?) { state.value = value }
}

private class FakeLibrary(val watched: List<WatchEntry>, val favs: List<Anime> = emptyList()) : LibraryRepository {
    override fun recentSearches(limit: Int) = flowOf(emptyList<String>())
    override suspend fun addSearch(query: String) = Unit
    override suspend fun removeSearch(query: String) = Unit
    override suspend fun clearSearches() = Unit
    override fun continueWatching() = flowOf(watched)
    override fun progressForAnime(providerId: String, animeId: String) = flowOf(emptyList<EpisodeProgress>())
    override suspend fun getProgress(providerId: String, animeId: String, episodeNumber: String): EpisodeProgress? = null
    override suspend fun saveProgress(anime: Anime, episodeNumber: String, positionMs: Long, durationMs: Long) = Unit
    override suspend fun removeFromHistory(providerId: String, animeId: String) = Unit
    override fun favorites() = flowOf(favs)
    override fun isFavorite(providerId: String, animeId: String) = flowOf(false)
    override suspend fun setFavorite(anime: Anime, favorite: Boolean) = Unit
}

private class FakeSettings : SettingsRepository {
    override val settings = flowOf(AppSettings())
    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
}

private class FakeProvider(private val catalog: Map<String, List<Anime>>) : AnimeProvider {
    override val id = "hianime"
    override val displayName = "Fake"
    override suspend fun search(query: String, mode: TranslationMode) = catalog[query] ?: emptyList()
    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> = emptyList()
    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet = error("unused")
}

private fun a(id: String, title: String, cover: String? = "c") = Anime(id, "hianime", title, cover)
private fun w(anime: Anime, frac: Float = 0.95f) = WatchEntry(anime, EpisodeProgress("hianime", anime.id, "1", (frac * 100).toLong(), 100, 0))

class RecommendationServiceTest {
    private val frieren = a("f", "Frieren")
    private val catalog = mapOf(
        "Mushishi" to listOf(a("m", "Mushishi")),
        "Frieren" to listOf(frieren),                       // already watched: must be dropped
        "NoCover" to listOf(a("n", "NoCover", cover = null)), // not displayable: dropped
        "Monster" to listOf(a("x", "Totally Unrelated"), a("mo", "Monster")),
        "Ghost" to emptyList(),                             // provider has nothing: dropped
        "Haibane" to listOf(a("h", "Haibane Renmei")),
    )

    private fun service(engine: FakeEngine, cache: FakeCache = FakeCache(), now: Long = 1_000, library: LibraryRepository = FakeLibrary(listOf(w(frieren)))) =
        RecommendationService(engine, library, FakeSettings(), mapOf("hianime" to FakeProvider(catalog)), cache, RecommendationService.Clock { now })

    @Test fun endToEndFiltersUnwatchableAndWatched() = runTest {
        val json = """```json
            [{"title":"Mushishi","reason":"Calm"},{"title":"Frieren","reason":"seen"},{"title":"NoCover","reason":"x"},
             {"title":"Monster","reason":"Tense"},{"title":"Ghost","reason":"?"},{"title":"Haibane","reason":"Soft"},{"title":"Mushishi","reason":"dup"}]
            ```"""
        val engine = FakeEngine(json)
        val cache = FakeCache()
        val result = service(engine, cache).generate()
        assertEquals(listOf("Mushishi", "Monster", "Haibane Renmei"), result.items.map { it.anime.title })
        assertEquals("Calm", result.items.first().reason)
        assertTrue(engine.lastPrompt.contains("Frieren"))
        assertEquals(result, cache.state.value)
    }

    @Test fun noHistoryThrowsAndNeverCallsEngine() = runTest {
        val engine = FakeEngine("[]")
        try { service(engine, library = FakeLibrary(emptyList())).generate(); fail() } catch (_: NoHistoryException) {}
        assertEquals(0, engine.calls)
    }

    @Test fun nothingResolvableThrowsNoResultsAndKeepsOldCache() = runTest {
        val cache = FakeCache()
        val old = CachedRecommendations(listOf(Recommendation(a("m", "Mushishi"), "r")), emptyList(), 1)
        cache.save(old)
        try { service(FakeEngine("""[{"title":"Ghost","reason":""}]"""), cache).generate(); fail() } catch (_: NoResultsException) {}
        assertEquals(old, cache.cached.first())
    }

    @Test fun garbageModelOutputIsNoResults() = runTest {
        try { service(FakeEngine("I am sorry, I can't do that.")).generate(); fail() } catch (_: NoResultsException) {}
    }

    @Test fun stalenessRules() = runTest {
        val svc = service(FakeEngine(""), now = 10_000)
        val snap = HistorySnapshot(listOf(w(frieren)), emptyList())
        assertTrue(svc.isStale(null, snap))
        val fresh = CachedRecommendations(emptyList(), snap.signature, 9_000)
        assertFalse(svc.isStale(fresh, snap))
        val bigger = HistorySnapshot(listOf(w(frieren), w(a("1", "A")), w(a("2", "B"))), emptyList())
        assertTrue(svc.isStale(fresh, bigger))
        assertTrue(service(FakeEngine(""), now = 9_000 + RecommendationService.MAX_AGE_MS + 1).isStale(fresh, snap))
    }

    @Test fun bestMatchRequiresRealOverlap() {
        assertNull(RecommendationService.bestMatch("Monster", listOf(a("x", "Totally Unrelated"))))
        assertEquals("mo", RecommendationService.bestMatch("Monster", listOf(a("x", "Unrelated"), a("mo", "Monster")))?.id)
        assertEquals("s", RecommendationService.bestMatch("Frieren", listOf(a("s", "Sousou no Frieren")))?.id)
    }
}
