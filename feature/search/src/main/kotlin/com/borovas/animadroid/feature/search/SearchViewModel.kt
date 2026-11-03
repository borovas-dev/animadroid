package com.borovas.animadroid.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.LibraryRepository
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.CloudflareSolved
import com.borovas.animadroid.provider.api.ProviderChain
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
    /** [message] null = show a localized default (generic failure, or "no provider" when [noProvider]); [cloudflareUrl] set = browser check needed. */
    data class Error(val message: String?, val cloudflareUrl: String? = null, val noProvider: Boolean = false) : SearchResults
}

data class SearchUiState(
    val query: String = "",
    val mode: TranslationMode = TranslationMode.SUB,
    val recents: List<String> = emptyList(),
    val results: SearchResults = SearchResults.Idle,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chain: ProviderChain,
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

    init {
        viewModelScope.launch {
            CloudflareSolved.events.collect {
                val r = results.value
                if (r is SearchResults.Error && r.cloudflareUrl != null) retry()
            }
        }
    }

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
            if (chain.chainFor(s).isEmpty()) {
                results.value = SearchResults.Error(null, noProvider = true)
                return@launch
            }
            results.value = try {
                val m = modeOverride ?: s.mode
                val found = chain.search(q, m)
                SearchResults.Success(
                    if (m == TranslationMode.DUB) found.filter { dubModeVisible(it, chain.provider(it.providerId)?.reportsDubAvailability == true, s.dubEnabled) } else found,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareChallengeException) {
                SearchResults.Error(null, e.url)
            } catch (e: Exception) {
                SearchResults.Error(e.message)
            }
        }
    }
}


/**
 * Whether a search hit is listed in DUB mode. With the generated voice-over on, every show is (it can be read from its subtitles).
 * Otherwise only shows with a recorded dub; when the provider can't say ([providerKnowsDubs] false) the show stays visible.
 */
internal fun dubModeVisible(anime: Anime, providerKnowsDubs: Boolean, voiceOverEnabled: Boolean): Boolean =
    voiceOverEnabled || !providerKnowsDubs || (anime.dubEpisodes ?: 0) > 0
