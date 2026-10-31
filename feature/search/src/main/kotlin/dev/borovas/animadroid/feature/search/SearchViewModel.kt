package dev.borovas.animadroid.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.LibraryRepository
import dev.borovas.animadroid.core.model.SettingsRepository
import dev.borovas.animadroid.core.model.TranslationMode
import dev.borovas.animadroid.provider.api.AnimeProvider
import dev.borovas.animadroid.provider.api.CloudflareChallengeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object SearchRoute : NavKey

sealed interface SearchResults {
    data object Idle : SearchResults
    data object Loading : SearchResults
    data class Success(val items: List<Anime>) : SearchResults
    data class Error(val message: String, val cloudflareUrl: String? = null) : SearchResults
}

data class SearchUiState(
    val query: String = "",
    val mode: TranslationMode = TranslationMode.SUB,
    val recents: List<String> = emptyList(),
    val results: SearchResults = SearchResults.Idle,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val results = MutableStateFlow<SearchResults>(SearchResults.Idle)
    private val query = MutableStateFlow("")
    private var job: Job? = null

    val state: StateFlow<SearchUiState> = combine(
        query, settings.settings, library.recentSearches(), results,
    ) { q, s, recents, r -> SearchUiState(q, s.mode, recents, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun search(raw: String) {
        val q = raw.trim()
        query.value = raw
        if (q.isEmpty()) return
        viewModelScope.launch { library.addSearch(q) }
        runSearch(q)
    }

    fun retry() = search(query.value)

    fun setMode(mode: TranslationMode) {
        viewModelScope.launch {
            settings.update { it.copy(mode = mode) }
            if (query.value.isNotBlank() && results.value !is SearchResults.Idle) runSearch(query.value.trim(), mode)
        }
    }

    fun removeRecent(q: String) { viewModelScope.launch { library.removeSearch(q) } }
    fun clearRecents() { viewModelScope.launch { library.clearSearches() } }

    private fun runSearch(q: String, modeOverride: TranslationMode? = null) {
        job?.cancel()
        results.value = SearchResults.Loading
        job = viewModelScope.launch {
            val s = settings.settings.first()
            val provider = providers[s.providerId] ?: providers.values.firstOrNull()
            if (provider == null) {
                results.value = SearchResults.Error("No anime provider is available.")
                return@launch
            }
            results.value = try {
                SearchResults.Success(provider.search(q, modeOverride ?: s.mode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                SearchResults.Error("Cloudflare is asking for a browser check.", e.url)
            } catch (e: Exception) {
                SearchResults.Error(e.message ?: "Search failed.")
            }
        }
    }
}

