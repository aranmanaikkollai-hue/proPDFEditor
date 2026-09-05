package com.propdfeditor.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.usecase.GetDashboardDataUseCase
import com.propdf.editor.data.local.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getDashboardData: GetDashboardDataUseCase,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Settings' Dark Mode / Dynamic Colors toggles previously wrote to Compose state
    // local to SettingsScreen only -- MainActivity's ProPDFTheme(...) call never saw
    // them and always fell back to its own defaults. Reading the same persisted
    // DataStore values here, at the app root, is what actually makes the toggle apply
    // app-wide and survive process death.
    // Falls back to the system theme (matching ProPDFTheme's original default) until
    // the user has explicitly chosen a mode in Settings.
    val isDarkMode: StateFlow<Boolean?> = settingsDataStore.isDarkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isDynamicColor: StateFlow<Boolean> = settingsDataStore.isDynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            // Minimum splash duration + initialization
            delay(400)
            _uiState.value = MainUiState.Ready(hasPendingDeepLink = false)
        }
    }
}

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val hasPendingDeepLink: Boolean) : MainUiState
}
