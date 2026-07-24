package com.propdf.editor.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.editor.domain.model.PdfDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val recentFilesRepo: RecentFilesRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        observeFiles()
    }

    private fun observeFiles() {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.observeAll().collectLatest { files ->
                val docs = files.map { file ->
                    PdfDocument(
                        id = file.uri,
                        uri = android.net.Uri.parse(file.uri),
                        displayName = file.displayName,
                        fileSize = file.fileSizeBytes,
                        pageCount = file.pageCount,
                        lastModified = file.lastOpenedAt,
                        isFavorite = file.isFavourite,
                        isDeleted = false,
                        category = com.propdf.editor.domain.model.DocumentCategory.UNCATEGORIZED,
                        cloudProvider = null
                    )
                }
                _uiState.update { it.copy(allFiles = docs) }
                applySearch()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applySearch()
    }

    private fun applySearch() {
        val state = _uiState.value
        val results = if (state.searchQuery.isEmpty()) {
            emptyList()
        } else {
            state.allFiles.filter {
                it.displayName.contains(state.searchQuery, ignoreCase = true)
            }
        }
        _uiState.update { it.copy(results = results) }
    }
}

data class SearchUiState(
    val searchQuery: String = "",
    val allFiles: List<PdfDocument> = emptyList(),
    val results: List<PdfDocument> = emptyList()
)
