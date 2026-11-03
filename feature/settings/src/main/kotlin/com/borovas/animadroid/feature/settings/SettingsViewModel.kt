package com.borovas.animadroid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.provider.api.ProviderChain
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object SettingsRoute : NavKey

/** One row of the "Advanced: sources" list: a provider in the chain for the current content language. */
data class SourceItem(val id: String, val name: String, val enabled: Boolean)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** Selectable content languages: "auto" first, then codes. */
    val languages: List<String> = BASE_LANGUAGES,
    /** Concrete language the chain below is built for. */
    val effectiveLanguage: String = "en",
    val sources: List<SourceItem> = emptyList(),
)

internal val BASE_LANGUAGES = listOf("auto", "en", "pl", "zh", "ru", "pt")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val chain: ProviderChain,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = repo.settings
        .map { SettingsUiState(it, languages(), chain.effectiveLanguage(it), sources(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    /** Moves [id] by [delta] positions (-1 up, +1 down) in the current language's order. */
    fun moveSource(id: String, delta: Int) = update { s ->
        val lang = chain.effectiveLanguage(s)
        val ids = fullChain(s).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) s
        else {
            ids.add(to, ids.removeAt(from))
            s.copy(providerOrder = s.providerOrder + (lang to ids))
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) = update { s ->
        s.copy(disabledProviders = if (enabled) s.disabledProviders - id else s.disabledProviders + id)
    }

    private fun fullChain(s: AppSettings) = chain.chainFor(s.copy(disabledProviders = emptySet()))

    private fun sources(s: AppSettings) =
        fullChain(s).map { SourceItem(it.id, it.displayName, it.id !in s.disabledProviders) }

    private fun languages(): List<String> {
        val extra = chain.ids.flatMap { chain.provider(it)?.contentLanguages.orEmpty() }.toSortedSet() - BASE_LANGUAGES.toSet()
        return BASE_LANGUAGES + extra
    }
}
