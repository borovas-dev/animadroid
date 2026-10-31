package dev.borovas.animadroid.feature.recommend

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object RecommendRoute : NavKey

data class RecommendUiState(
    /** Null until the first model state arrives. */
    val model: ModelState? = null,
    val items: List<Recommendation> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
) {
    val modelReady get() = model is ModelState.Ready
}

/** Backs both the home card and the full screen. Starts no model work until the model is Ready and the list is stale. */
@HiltViewModel
class RecommendViewModel @Inject constructor(
    private val models: ModelRepository,
    private val service: RecommendationService,
) : ViewModel() {
    private val generating = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var autoTried = false

    val state: StateFlow<RecommendUiState> = combine(models.state, service.cached, generating, error) { m, c, g, e ->
        RecommendUiState(m, c?.items.orEmpty(), g, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecommendUiState())

    init {
        viewModelScope.launch {
            // Auto-generate once per session when the model is ready and the cache is missing or out of date.
            models.state.first { it is ModelState.Ready }
            if (autoTried) return@launch
            autoTried = true
            val snap = service.snapshot()
            if (!snap.isEmpty && service.isStale(service.cached.first(), snap)) refresh()
        }
    }

    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            generating.value = true; error.value = null
            try {
                service.generate()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error.value = t.message ?: "Could not generate recommendations."
            } finally {
                generating.value = false
            }
        }
    }
}

data class RecommendSettingsUiState(
    val model: ModelState = ModelState.NotInstalled,
    val wifiOnly: Boolean = true,
    val token: String = "",
    val problems: List<RequirementProblem> = emptyList(),
)

@HiltViewModel
class RecommendSettingsViewModel @Inject constructor(
    private val models: ModelRepository,
    private val prefs: RecommendPrefs,
    private val engine: LlmEngine,
    private val service: RecommendationService,
) : ViewModel() {
    private val problems = MutableStateFlow<List<RequirementProblem>>(emptyList())

    val state: StateFlow<RecommendSettingsUiState> = combine(models.state, prefs.wifiOnly, prefs.hfToken, problems) { m, w, t, p ->
        RecommendSettingsUiState(m, w, t, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecommendSettingsUiState())

    fun refreshRequirements() { problems.value = models.checkRequirements() }

    fun download() {
        viewModelScope.launch { problems.value = models.startDownload() }
    }

    fun cancel() = models.cancelDownload()
    fun importFile(uri: Uri) = models.importFrom(uri)
    fun setWifiOnly(v: Boolean) { viewModelScope.launch { prefs.setWifiOnly(v) } }
    fun setToken(v: String) { viewModelScope.launch { prefs.setHfToken(v) } }

    fun delete() {
        viewModelScope.launch {
            engine.release()
            models.delete()
            service.clearCache()
        }
    }
}
