package com.borovas.animadroid.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.DownloadController
import com.borovas.animadroid.core.model.DownloadGroup
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadRepository
import com.borovas.animadroid.core.model.Episode
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val groups: List<DownloadGroup> = emptyList(),
    val totalBytes: Long = 0,
    val waitingForNetwork: Boolean = false,
    val loading: Boolean = true,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    repository: DownloadRepository,
    private val controller: DownloadController,
    private val coordinator: DownloadCoordinator,
) : ViewModel() {

    init {
        coordinator.manager // create the manager early so persisted downloads resume and Room is reconciled
    }

    val messages: SharedFlow<String> = coordinator.messages

    val state: StateFlow<DownloadsUiState> = combine(repository.groupedByAnime(), coordinator.waitingForNetwork) { groups, waiting ->
        DownloadsUiState(groups, groups.sumOf { it.totalBytes }, waiting && groups.any { g -> g.items.any { it.state.isActive } }, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun pause(item: DownloadItem) = controller.pause(item.providerId, item.animeId, item.episodeNumber)
    fun resume(item: DownloadItem) = controller.resume(item.providerId, item.animeId, item.episodeNumber)
    fun remove(item: DownloadItem) = controller.remove(item.providerId, item.animeId, item.episodeNumber)
    fun removeGroup(group: DownloadGroup) = group.items.forEach(::remove)

    fun retry(item: DownloadItem) {
        viewModelScope.launch {
            controller.enqueue(
                Anime(item.animeId, item.providerId, item.title, coverUrl = item.coverUrl),
                Episode(item.episodeId, item.episodeNumber), item.mode,
            )
        }
    }
}
