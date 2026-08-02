package com.propdfeditor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.DashboardData
import com.propdf.core.domain.usecase.GetDashboardDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getDashboardData: GetDashboardDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.Refresh -> loadDashboard()
            is HomeEvent.LaunchFilePicker -> { /* Handled by Activity/SAF launcher */ }
            is HomeEvent.PinFile -> togglePin(event.uri)
            is HomeEvent.FavoriteFile -> toggleFavorite(event.uri)
            is HomeEvent.DeleteFile -> softDelete(event.uri)
        }
    }

    private fun loadDashboard() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            getDashboardData()
                .onSuccess { data ->
                    _uiState.value = if (data.isEmpty()) {
                        HomeUiState.Empty
                    } else {
                        HomeUiState.Success(data)
                    }
                }
                .onFailure { e ->
                    _uiState.value = HomeUiState.Error(e.message ?: "Unknown error")
                }
        }
    }

    private fun togglePin(uri: String) {
        viewModelScope.launch {
            // Delegate to repository
        }
    }

    private fun toggleFavorite(uri: String) {
        viewModelScope.launch {
            // Delegate to repository
        }
    }

    private fun softDelete(uri: String) {
        viewModelScope.launch {
            // Move to recycle bin
        }
    }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data class Success(val data: DashboardData) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

sealed interface HomeEvent {
    data object Refresh : HomeEvent
    data object LaunchFilePicker : HomeEvent
    data class PinFile(val uri: String) : HomeEvent
    data class FavoriteFile(val uri: String) : HomeEvent
    data class DeleteFile(val uri: String) : HomeEvent
}
