package com.propdf.editor.ui.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.domain.model.*
import com.propdf.editor.domain.usecase.GetRecentFilesUseCase
import com.propdf.editor.domain.usecase.ToggleFavoriteUseCase
import com.propdf.editor.domain.usecase.MoveToRecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentFilesViewModel @Inject constructor(
    private val getRecentFiles: GetRecentFilesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val moveToRecycleBin: MoveToRecycleBinUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentFilesUiState())
    val uiState: StateFlow<RecentFilesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        viewModelScope.launch {
            getRecentFiles().collect { files ->
                _uiState.update { it.copy(files = files, isLoading = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(id: Long, currentState: Boolean) {
        viewModelScope.launch {
            toggleFavorite(id, currentState)
        }
    }

    fun deleteFile(id: Long) {
        viewModelScope.launch {
            moveToRecycleBin(id)
        }
    }
}

data class RecentFilesUiState(
    val files: List<PdfDocument> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
