package com.borovas.animadroid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object SettingsRoute : NavKey

data class SettingsUiState(val settings: AppSettings = AppSettings())

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsUiState> = kotlinx.coroutines.flow.flow {
        repo.settings.collect { emit(SettingsUiState(it)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }
}
