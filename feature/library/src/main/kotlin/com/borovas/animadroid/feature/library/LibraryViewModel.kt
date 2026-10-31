package com.borovas.animadroid.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.WatchEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object LibraryRoute : NavKey

data class LibraryUiState(
    val continueWatching: List<WatchEntry> = emptyList(),
    val favorites: List<Anime> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(private val library: LibraryRepository) : ViewModel() {
    val state: StateFlow<LibraryUiState> = combine(library.continueWatching(), library.favorites()) { c, f ->
        LibraryUiState(c, f, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun removeFromHistory(anime: Anime) {
        viewModelScope.launch { library.removeFromHistory(anime.providerId, anime.id) }
    }
}
