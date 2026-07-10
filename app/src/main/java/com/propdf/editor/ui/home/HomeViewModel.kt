package com.propdf.editor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val recentFiles: List<PdfDocument> = emptyList(),
    val collections: List<DocumentCollection> = emptyList(),
    val tags: List<DocumentTag> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository
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
                documentRepository.getRecentDocuments(10),
                collectionRepository.getAllCollections(),
                tagRepository.getAllTags(),
                documentRepository.getDocumentCount(),
                documentRepository.getTotalSize()
            ) { recent, collections, tags, count, totalSize ->
                val stats = StorageStats(
                    totalDocuments = count,
                    totalSize = totalSize ?: 0,
                    favoriteCount = recent.count { it.isFavorite },
                    deletedCount = 0 // Would need separate query
                )
                HomeUiState(
                    recentFiles = recent,
                    collections = collections,
                    tags = tags,
                    storageStats = stats,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
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
