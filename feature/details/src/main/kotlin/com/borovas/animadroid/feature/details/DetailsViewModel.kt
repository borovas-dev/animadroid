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
import com.borovas.animadroid.provider.api.CloudflareChallengeException
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

data class PlayTarget(val index: Int, val label: String)

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
    val error: String? = null,
    val cloudflareUrl: String? = null,
) {
    /** Resume like ani-cli: latest watched episode, or the next one if it was finished. */
    val playTarget: PlayTarget?
        get() {
            if (episodes.isEmpty()) return null
            val latest = progress.values.maxByOrNull { it.updatedAt }
            val idx = latest?.let { l -> episodes.indexOfFirst { it.number == l.episodeNumber } } ?: -1
            return when {
                latest == null || idx < 0 -> PlayTarget(0, "Play Ep ${episodes[0].number}")
                !latest.isFinished -> PlayTarget(idx, "Continue Ep ${episodes[idx].number}")
                idx + 1 < episodes.size -> PlayTarget(idx + 1, "Continue Ep ${episodes[idx + 1].number}")
                else -> PlayTarget(idx, "Replay Ep ${episodes[idx].number}")
            }
        }
}

@HiltViewModel(assistedFactory = DetailsViewModel.Factory::class)
class DetailsViewModel @AssistedInject constructor(
    @Assisted private val initial: Anime,
    private val providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
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
        val modes = availableModes(l.anime)
        val mode = (override ?: s.mode).takeIf { it in modes } ?: modes.first()
        DetailsUiState(
            anime = l.anime, mode = mode, availableModes = modes, episodes = l.episodes,
            progress = progress.associateBy { it.episodeNumber }, downloads = downloads, isFavorite = fav,
            loading = l.loading, error = l.error, cloudflareUrl = l.cloudflareUrl,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState(initial))

    init { reload() }

    private fun availableModes(a: Anime): List<TranslationMode> {
        if (a.subEpisodes == null && a.dubEpisodes == null) return TranslationMode.entries
        val modes = buildList {
            if ((a.subEpisodes ?: 0) > 0) add(TranslationMode.SUB)
            if ((a.dubEpisodes ?: 0) > 0) add(TranslationMode.DUB)
        }
        return modes.ifEmpty { TranslationMode.entries }
    }

    private suspend fun provider(): AnimeProvider? {
        val id = settings.settings.first().providerId
        return providers[initial.providerId] ?: providers[id] ?: providers.values.firstOrNull()
    }

    fun reload() {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            load.value = load.value.copy(loading = true, error = null, cloudflareUrl = null)
            val p = provider()
            if (p == null) {
                load.value = load.value.copy(loading = false, error = "No anime provider is available.")
                return@launch
            }
            try {
                val enriched = runCatching { p.details(initial) }.getOrDefault(initial)
                load.value = load.value.copy(anime = enriched)
                fetchEpisodes(p, enriched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun fetchEpisodes(p: AnimeProvider, anime: Anime, mode: TranslationMode? = null) {
        val m = mode ?: (modeOverride.value ?: settings.settings.first().mode).let { c -> availableModes(anime).takeIf { c in it }?.let { c } ?: availableModes(anime).first() }
        try {
            val eps = p.episodes(anime, m)
            load.value = load.value.copy(episodes = eps, loading = false, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        load.value = load.value.copy(
            loading = false,
            error = if (e is CloudflareChallengeException) "Cloudflare is asking for a browser check." else e.message ?: "Could not load episodes.",
            cloudflareUrl = (e as? CloudflareChallengeException)?.url,
        )
    }

    fun setMode(mode: TranslationMode) {
        modeOverride.value = mode
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            load.value = load.value.copy(loading = true, episodes = emptyList(), error = null)
            val p = provider() ?: return@launch
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
