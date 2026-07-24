package com.propdf.editor.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.DocumentCategory
import com.propdf.editor.domain.model.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val recentFilesRepo: RecentFilesRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    private fun loadFiles() {
        viewModelScope.launch(dispatchers.io) {
            recentFilesRepo.observeAll().collectLatest { files ->
                applyFilters(files)
            }
        }
    }

    private fun applyFilters(files: List<RecentFile>) {
        val state = _uiState.value
        var filtered = files.map { it.toPdfDocument() }

        // Apply search
        if (state.searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.displayName.contains(state.searchQuery, ignoreCase = true)
            }
        }

        // Apply sort
        filtered = when (state.sortField) {
            SortField.DATE -> if (state.sortAsc) filtered.sortedBy { it.lastModified }
            else filtered.sortedByDescending { it.lastModified }
            SortField.NAME -> if (state.sortAsc) filtered.sortedBy { it.displayName.lowercase() }
            else filtered.sortedByDescending { it.displayName.lowercase() }
            SortField.SIZE -> if (state.sortAsc) filtered.sortedBy { it.fileSize }
            else filtered.sortedByDescending { it.fileSize }
        }

        _uiState.update { it.copy(files = filtered) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSort(field: SortField, asc: Boolean) {
        _uiState.update { it.copy(sortField = field, sortAsc = asc) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch(dispatchers.io) {
            // Implementation depends on repository
        }
    }

    fun moveToRecycleBin(id: String) {
        viewModelScope.launch(dispatchers.io) {
            // Implementation depends on repository
        }
    }

    private fun RecentFile.toPdfDocument(): PdfDocument {
        return PdfDocument(
            id = uri,
            uri = android.net.Uri.parse(uri),
            displayName = displayName,
            fileSize = fileSizeBytes,
            pageCount = pageCount,
            lastModified = lastOpenedAt,
            isFavorite = isFavourite,
            isDeleted = false,
            category = DocumentCategory.UNCATEGORIZED,
            cloudProvider = null
        )
    }
}

data class FilesUiState(
    val files: List<PdfDocument> = emptyList(),
    val searchQuery: String = "",
    val sortField: SortField = SortField.DATE,
    val sortAsc: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val isLoading: Boolean = false
)
