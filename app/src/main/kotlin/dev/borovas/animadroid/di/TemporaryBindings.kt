// =====================================================================================
// TEMPORARY - DELETE THIS WHOLE FILE once :core:database, :core:datastore and
// :provider:hianime provide real Hilt bindings for LibraryRepository, SettingsRepository
// and the Map<String, AnimeProvider> multibinding. Left here only so :app builds and
// runs (with demo data) before those modules are merged.
// =====================================================================================
package dev.borovas.animadroid.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.Multibinds
import dagger.multibindings.StringKey
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.AppSettings
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.core.model.EpisodeProgress
import dev.borovas.animadroid.core.model.LibraryRepository
import dev.borovas.animadroid.core.model.SettingsRepository
import dev.borovas.animadroid.core.model.StreamSet
import dev.borovas.animadroid.core.model.TranslationMode
import dev.borovas.animadroid.core.model.WatchEntry
import dev.borovas.animadroid.provider.api.AnimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TemporaryBindings {
    @Binds @Singleton abstract fun library(impl: FakeLibraryRepository): LibraryRepository
    @Binds @Singleton abstract fun settings(impl: FakeSettingsRepository): SettingsRepository
    @Binds @IntoMap @StringKey("demo") abstract fun demoProvider(impl: FakeProvider): AnimeProvider

    @Multibinds abstract fun providers(): Map<String, @JvmSuppressWildcards AnimeProvider>
}

@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {
    private val state = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}

private fun cover(seed: String) = "https://picsum.photos/seed/$seed/300/450"

@Singleton
class FakeProvider @Inject constructor() : AnimeProvider {
    override val id = "demo"
    override val displayName = "Demo"
    private val shows = listOf(
        Anime("1", "demo", "Frieren: Beyond Journey's End", cover("frieren"), "The demon king has been defeated, and the victorious hero party returns home. The elf mage Frieren then sets out on a new journey to understand humans better and to fulfil a final promise.", 28, 28, 2023),
        Anime("2", "demo", "Cowboy Bebop", cover("bebop"), "A ragtag crew of bounty hunters chase criminals across the solar system.", 26, 26, 1998),
        Anime("3", "demo", "Mushishi", cover("mushishi"), "Ginko travels the countryside studying strange life forms known as mushi.", 26, null, 2005),
        Anime("4", "demo", "Monster", cover("monster"), null, 74, null, 2004),
        Anime("5", "demo", "Steins;Gate", cover("steins"), null, 24, 24, 2011),
        Anime("6", "demo", "Violet Evergarden", cover("violet"), null, 13, 13, 2018),
    )
    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        delay(600)
        return shows.filter { query.isBlank() || it.title.contains(query, true) || query.length < 3 }
            .filter { if (mode == TranslationMode.DUB) (it.dubEpisodes ?: 0) > 0 else true }
    }
    override suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode> {
        delay(400)
        val n = (if (mode == TranslationMode.SUB) anime.subEpisodes else anime.dubEpisodes) ?: 12
        return (1..n).map { Episode("${anime.id}-$it", "$it") }
    }
    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet =
        error("Demo provider has no streams")
}

@Singleton
class FakeLibraryRepository @Inject constructor() : LibraryRepository {
    private val searches = MutableStateFlow(listOf("frieren", "bebop"))
    private val progress = MutableStateFlow(
        listOf(
            demo("1", "Frieren: Beyond Journey's End", "frieren", "7", 600_000, 1_400_000),
            demo("2", "Cowboy Bebop", "bebop", "12", 1_000_000, 1_400_000),
            demo("3", "Mushishi", "mushishi", "3", 300_000, 1_400_000),
        ).map { it.last }
    )
    private val animes = MutableStateFlow(
        mapOf(
            "1" to Anime("1", "demo", "Frieren: Beyond Journey's End", cover("frieren"), subEpisodes = 28, dubEpisodes = 28),
            "2" to Anime("2", "demo", "Cowboy Bebop", cover("bebop"), subEpisodes = 26, dubEpisodes = 26),
            "3" to Anime("3", "demo", "Mushishi", cover("mushishi"), subEpisodes = 26),
        )
    )
    private val favs = MutableStateFlow(
        listOf(Anime("5", "demo", "Steins;Gate", cover("steins"), subEpisodes = 24, dubEpisodes = 24), Anime("4", "demo", "Monster", cover("monster"), subEpisodes = 74))
    )

    private fun demo(id: String, title: String, seed: String, ep: String, pos: Long, dur: Long) =
        WatchEntry(Anime(id, "demo", title, cover(seed)), EpisodeProgress("demo", id, ep, pos, dur, System.currentTimeMillis()))

    override fun recentSearches(limit: Int) = searches.map { it.take(limit) }
    override suspend fun addSearch(query: String) = searches.update { listOf(query) + it.filter { s -> s != query } }
    override suspend fun removeSearch(query: String) = searches.update { it - query }
    override suspend fun clearSearches() = searches.update { emptyList() }

    override fun continueWatching(): Flow<List<WatchEntry>> = combine(progress, animes) { p, a ->
        p.groupBy { it.animeId }.mapNotNull { (id, l) -> a[id]?.let { WatchEntry(it, l.maxBy { e -> e.updatedAt }) } }
    }
    override fun progressForAnime(providerId: String, animeId: String) = progress.map { l -> l.filter { it.animeId == animeId } }
    override suspend fun getProgress(providerId: String, animeId: String, episodeNumber: String) =
        progress.value.firstOrNull { it.animeId == animeId && it.episodeNumber == episodeNumber }
    override suspend fun saveProgress(anime: Anime, episodeNumber: String, positionMs: Long, durationMs: Long) {
        animes.update { it + (anime.id to anime) }
        progress.update { l ->
            l.filterNot { it.animeId == anime.id && it.episodeNumber == episodeNumber } +
                EpisodeProgress(anime.providerId, anime.id, episodeNumber, positionMs, durationMs, System.currentTimeMillis())
        }
    }
    override suspend fun removeFromHistory(providerId: String, animeId: String) = progress.update { l -> l.filterNot { it.animeId == animeId } }

    override fun favorites(): Flow<List<Anime>> = favs
    override fun isFavorite(providerId: String, animeId: String) = favs.map { l -> l.any { it.id == animeId } }
    override suspend fun setFavorite(anime: Anime, favorite: Boolean) =
        favs.update { l -> l.filterNot { it.id == anime.id } + listOfNotNull(anime.takeIf { favorite }) }
}
