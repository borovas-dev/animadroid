package com.borovas.animadroid.feature.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.DownloadController
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadRepository
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.EpisodeProgress
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import com.borovas.animadroid.provider.api.ProviderChain
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.CloudflareSolved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class DetailsRoute(val anime: Anime) : NavKey

data class PlayTarget(val index: Int, val kind: Kind, val episode: String) {
    enum class Kind { PLAY, CONTINUE, REPLAY }
}

/**
 * Modes offered as chips. Counts unknown for both = all modes (unchanged). With the experimental voice-over on, DUB is
 * always offered (a generated voice-over can stand in for a missing recorded dub) as long as SUB episodes exist.
 */
internal fun availableModesFor(subEpisodes: Int?, dubEpisodes: Int?, voiceOver: Boolean): List<TranslationMode> {
    if (subEpisodes == null && dubEpisodes == null) return TranslationMode.entries
    val modes = buildList {
        if ((subEpisodes ?: 0) > 0) add(TranslationMode.SUB)
        if ((dubEpisodes ?: 0) > 0 || (voiceOver && (subEpisodes ?: 0) > 0)) add(TranslationMode.DUB)
    }
    return modes.ifEmpty { TranslationMode.entries }
}

data class DetailsUiState(
    val anime: Anime,
    val mode: TranslationMode = TranslationMode.SUB,
    val availableModes: List<TranslationMode> = TranslationMode.entries,
    val episodes: List<Episode> = emptyList(),
    val progress: Map<String, EpisodeProgress> = emptyMap(),
    /** Episode number -> offline download, when one exists. */
    val downloads: Map<String, DownloadItem> = emptyMap(),
    val isFavorite: Boolean = false,
    val loading: Boolean = true,
    /** Non-null = failed; blank means "show the localized default" (see [noProvider], [cloudflareUrl]). */
    val error: String? = null,
    val cloudflareUrl: String? = null,
    val noProvider: Boolean = false,
    /** DUB selected with voice-over on, but there is no recorded dub: [episodes] are the SUB list and the hint is shown. */
    val noRecordedDub: Boolean = false,
) {
    /** Resume like ani-cli: latest watched episode, or the next one if it was finished. */
    val playTarget: PlayTarget?
        get() {
            if (episodes.isEmpty()) return null
            val latest = progress.values.maxByOrNull { it.updatedAt }
            val idx = latest?.let { l -> episodes.indexOfFirst { it.number == l.episodeNumber } } ?: -1
            return when {
                latest == null || idx < 0 -> PlayTarget(0, PlayTarget.Kind.PLAY, episodes[0].number)
                !latest.isFinished -> PlayTarget(idx, PlayTarget.Kind.CONTINUE, episodes[idx].number)
                idx + 1 < episodes.size -> PlayTarget(idx + 1, PlayTarget.Kind.CONTINUE, episodes[idx + 1].number)
                else -> PlayTarget(idx, PlayTarget.Kind.REPLAY, episodes[idx].number)
            }
        }
}

@HiltViewModel(assistedFactory = DetailsViewModel.Factory::class)
class DetailsViewModel @AssistedInject constructor(
    @Assisted private val initial: Anime,
    private val chain: ProviderChain,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val downloadRepository: DownloadRepository,
    private val downloadController: DownloadController,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(anime: Anime): DetailsViewModel
    }

    private data class Load(
        val anime: Anime,
        val episodes: List<Episode> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val cloudflareUrl: String? = null,
        val noProvider: Boolean = false,
        val noRecordedDub: Boolean = false,
    )

    private val load = MutableStateFlow(Load(initial))
    private val modeOverride = MutableStateFlow<TranslationMode?>(null)
    private var episodesJob: Job? = null

    val state: StateFlow<DetailsUiState> = combine(
        load,
        modeOverride,
        settings.settings,
        combine(library.progressForAnime(initial.providerId, initial.id), downloadRepository.forAnime(initial.providerId, initial.id)) { p, d -> p to d },
        library.isFavorite(initial.providerId, initial.id),
    ) { l, override, s, (progress, downloads), fav ->
        val modes = availableModes(l.anime, s.dubEnabled)
        val mode = (override ?: s.mode).takeIf { it in modes } ?: modes.first()
        DetailsUiState(
            anime = l.anime, mode = mode, availableModes = modes, episodes = l.episodes,
            progress = progress.associateBy { it.episodeNumber }, downloads = downloads, isFavorite = fav,
            loading = l.loading, error = l.error, cloudflareUrl = l.cloudflareUrl, noProvider = l.noProvider,
            noRecordedDub = l.noRecordedDub && s.dubEnabled && mode == TranslationMode.DUB,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(initial))

    init {
        reload()
        viewModelScope.launch {
            CloudflareSolved.events.collect { if (load.value.cloudflareUrl != null) reload() }
        }
    }

    private fun availableModes(a: Anime, voiceOver: Boolean) = availableModesFor(a.subEpisodes, a.dubEpisodes, voiceOver)

    private suspend fun provider(): AnimeProvider? {
        // The entry's own provider first (even if hidden from the chain, e.g. a library item); else the best provider for the language.
        return chain.provider(initial.providerId) ?: chain.chain().firstOrNull()
    }

    fun reload() {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            load.value = load.value.copy(loading = true, error = null, cloudflareUrl = null, noProvider = false)
            val p = provider()
            if (p == null) {
                load.value = load.value.copy(loading = false, error = "", noProvider = true)
                return@launch
            }
            try {
                val enriched = runCatching { p.details(initial) }.getOrDefault(initial)
                    .let { if (it.alternates.isEmpty()) it.copy(alternates = initial.alternates) else it }
                load.value = load.value.copy(anime = enriched)
                fetchEpisodes(p, enriched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun fetchEpisodes(p: AnimeProvider, anime: Anime, mode: TranslationMode? = null, subStandsInForDub: Boolean = false) {
        val voiceOver = settings.settings.first().dubEnabled
        val modes = availableModes(anime, voiceOver)
        val m = mode ?: (modeOverride.value ?: settings.settings.first().mode).let { c -> if (c in modes) c else modes.first() }
        // Voice-over on: a DUB list without a recorded dub falls back to the (identically numbered) SUB list.
        val dubFallbackAllowed = voiceOver && m == TranslationMode.DUB
        if (dubFallbackAllowed && anime.dubEpisodes == 0) {
            return fetchEpisodes(p, anime, TranslationMode.SUB, subStandsInForDub = true)
        }
        var primaryFailure: Exception? = null
        val primary = try {
            p.episodes(anime, m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            primaryFailure = e
            emptyList()
        }
        if (primary.isNotEmpty()) {
            load.value = load.value.copy(episodes = primary, loading = false, error = null, noRecordedDub = subStandsInForDub)
            return
        }
        // The provider knows the show but has nothing playable (e.g. Shinden lists Death Note/Naruto without players):
        // switch to the first alternate provider that does have episodes, keeping the original as its own alternate.
        for ((altProviderId, altId) in anime.alternates) {
            val alt = chain.provider(altProviderId) ?: continue
            val altAnime = anime.copy(id = altId, providerId = altProviderId, alternates = anime.alternates - altProviderId + (anime.providerId to anime.id))
            val eps = try { alt.episodes(altAnime, m) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            if (eps.isNotEmpty()) {
                load.value = load.value.copy(anime = altAnime, episodes = eps, loading = false, error = null, noRecordedDub = subStandsInForDub)
                return
            }
        }
        if (dubFallbackAllowed && (primaryFailure == null || primaryFailure is com.borovas.animadroid.provider.api.ProviderException)) {
            return fetchEpisodes(p, anime, TranslationMode.SUB, subStandsInForDub = true)
        }
        fail(primaryFailure ?: com.borovas.animadroid.provider.api.ProviderException("No episodes found"))
    }

    private fun fail(e: Exception) {
        load.value = load.value.copy(
            loading = false,
            error = if (e is CloudflareChallengeException) "" else e.message ?: "",
            cloudflareUrl = (e as? CloudflareChallengeException)?.url,
        )
    }

    fun setMode(mode: TranslationMode) {
        modeOverride.value = mode
        // The player reads the translation mode from settings, so persist the choice.
        viewModelScope.launch { settings.update { it.copy(mode = mode) } }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            load.value = load.value.copy(loading = true, episodes = emptyList(), error = null)
            val p = chain.provider(load.value.anime.providerId) ?: provider() ?: return@launch
            fetchEpisodes(p, load.value.anime, mode)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { library.setFavorite(load.value.anime, !state.value.isFavorite) }
    }

    fun download(episode: Episode) {
        val anime = load.value.anime
        val mode = state.value.mode
        viewModelScope.launch { downloadController.enqueue(anime, episode, mode) }
    }

    /** Queues episodes [fromIndex]..[toIndex] (inclusive, 0-based) that are not downloaded yet. */
    fun downloadRange(fromIndex: Int, toIndex: Int) {
        val eps = state.value.episodes
        val existing = state.value.downloads
        val lo = fromIndex.coerceIn(0, eps.lastIndex.coerceAtLeast(0))
        val hi = toIndex.coerceIn(lo, eps.lastIndex.coerceAtLeast(0))
        val todo = eps.subList(lo, (hi + 1).coerceAtMost(eps.size))
            .filter { it.number !in existing || existing[it.number]?.state == com.borovas.animadroid.core.model.DownloadState.FAILED }
        val anime = load.value.anime
        val mode = state.value.mode
        if (todo.isNotEmpty()) viewModelScope.launch { downloadController.enqueueAll(anime, todo, mode) }
    }

    fun pauseDownload(episode: Episode) = downloadController.pause(initial.providerId, initial.id, episode.number)
    fun resumeDownload(episode: Episode) = downloadController.resume(initial.providerId, initial.id, episode.number)
    fun cancelDownload(episode: Episode) = downloadController.remove(initial.providerId, initial.id, episode.number)
}
