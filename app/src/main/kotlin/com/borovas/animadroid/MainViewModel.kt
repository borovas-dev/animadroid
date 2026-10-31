package com.borovas.animadroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Theme-related app state; [settings] is null until the first emission (keeps the splash screen up). */
data class MainUiState(val settings: AppSettings? = null)

@HiltViewModel
class MainViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {
    val state: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())
}
