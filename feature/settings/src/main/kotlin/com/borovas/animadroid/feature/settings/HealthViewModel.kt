package com.borovas.animadroid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.provider.api.ProviderChain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object HealthRoute : NavKey

data class HealthUiState(
    val running: Boolean = false,
    val language: String = "en",
    /** Effective chain order (display names) for [language]. */
    val chain: List<String> = emptyList(),
    val providers: List<ProviderHealth> = emptyList(),
)

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val chain: ProviderChain,
) : ViewModel() {
    private val runner = HealthRunner()
    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { run() }

    fun run() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val s = settings.settings.first()
            val inChain = chain.chainFor(s)
            val initial = chain.ids.mapNotNull { id ->
                chain.provider(id)?.let { p ->
                    ProviderHealth(p.id, p.displayName, disabled = p.id in s.disabledProviders, inChain = inChain.any { it.id == p.id })
                }
            }
            _state.value = HealthUiState(true, chain.effectiveLanguage(s), inChain.map { it.displayName }, initial)
            coroutineScope {
                initial.map { h ->
                    async {
                        runner.check(chain.provider(h.id)!!, h) { upd ->
                            _state.update { st -> st.copy(providers = st.providers.map { if (it.id == upd.id) upd else it }) }
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(running = false) }
        }
    }
}
