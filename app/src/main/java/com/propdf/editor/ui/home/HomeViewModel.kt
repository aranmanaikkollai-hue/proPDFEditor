package com.propdf.editor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.domain.model.*
import com.propdf.editor.domain.repository.DocumentRepository
import com.propdf.editor.domain.repository.FolderRepository
import com.propdf.editor.domain.usecase.GetRecentFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getRecentFiles: GetRecentFilesUseCase,
    private val documentRepository: DocumentRepository,
    private val folderRepository: FolderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            combine(
                getRecentFiles(),
                documentRepository.getStorageStats(),
                folderRepository.getAllFolders()
            ) { recent, stats, folders ->
                HomeUiState(
                    recentFiles = recent.take(10),
                    storageStats = stats,
                    folders = folders,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleFavorite(id: Long, currentState: Boolean) {
        viewModelScope.launch {
            documentRepository.setFavorite(id, !currentState)
        }
    }

    fun refresh() {
        loadData()
    }
}

data class HomeUiState(
    val recentFiles: List<PdfDocument> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val isLoading: Boolean = true,
    val error: String? = null
)
