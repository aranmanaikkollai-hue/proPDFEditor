package com.propdf.editor.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentFilesViewModel @Inject constructor(
    private val repository: RecentFilesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentFilesUiState())
    val uiState: StateFlow<RecentFilesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll()
                .catch { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
                .collect { files ->
                    _uiState.update { it.copy(files = files, isLoading = false) }
                }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            viewModelScope.launch {
                repository.observeAll()
                    .catch { }
                    .collect { files -> _uiState.update { it.copy(files = files) } }
            }
        } else {
            viewModelScope.launch {
                repository.search(query)
                    .catch { }
                    .collect { files -> _uiState.update { it.copy(files = files) } }
            }
        }
    }

    fun toggleFavourite(uri: String, current: Boolean) {
        viewModelScope.launch {
            repository.setFavourite(uri, !current)
        }
    }

    fun removeFile(uri: String) {
        viewModelScope.launch {
            repository.remove(uri)
        }
    }
}

data class RecentFilesUiState(
    val files: List<RecentFile> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
