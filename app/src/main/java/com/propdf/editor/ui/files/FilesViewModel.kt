package com.propdf.editor.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.DocumentCategory
import com.propdf.editor.domain.model.SortField
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
    private val documentRepository: com.propdf.editor.domain.repository.DocumentRepository,
    private val pdfDocumentDao: com.propdf.core.data.local.dao.PdfDocumentDao,
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
            SortField.DATE -> if (state.sortAsc) filtered.sortedBy { it.dateModified }
            else filtered.sortedByDescending { it.dateModified }
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

    fun toggleFavorite(id: Long) {
        viewModelScope.launch(dispatchers.io) {
            val doc = _uiState.value.files.find { it.id == id } ?: return@launch
            recentFilesRepo.setFavourite(doc.uri.toString(), !doc.isFavorite)
        }
    }

    fun moveToRecycleBin(id: Long) {
        viewModelScope.launch(dispatchers.io) {
            val doc = _uiState.value.files.find { it.id == id } ?: return@launch
            try {
                val realId = pdfDocumentDao.getByUri(doc.uri.toString())?.id
                if (realId != null) {
                    // Real, recoverable recycle-bin move — this is the same
                    // pdf_documents-backed path RecycleBinScreen/EmptyRecycleBinUseCase
                    // read from, so the file can actually be restored later.
                    documentRepository.deleteDocument(realId)
                } else {
                    // Document hasn't been indexed into pdf_documents yet (e.g. the
                    // dual-write backfill hasn't caught up). Falling back to removing
                    // it from the recent-files list so it doesn't look like the
                    // button did nothing, but note this path is NOT recoverable from
                    // the Recycle Bin screen — a real edge case, not the common path.
                    recentFilesRepo.remove(doc.uri.toString())
                }
            } catch (_: Exception) {
            }
        }
    }

    fun renameDocument(id: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
            val doc = _uiState.value.files.find { it.id == id } ?: return@launch
            recentFilesRepo.rename(doc.uri.toString(), newName.trim())
        }
    }

    private fun RecentFile.toPdfDocument(): PdfDocument {
        return PdfDocument(
            id = uri.hashCode().toLong(),
            uri = android.net.Uri.parse(uri),
            displayName = name,
            fileSize = size,
            dateModified = lastOpened,
            dateAdded = lastOpened,
            isFavorite = isFavorite,
            isDeleted = false,
            category = DocumentCategory.UNCATEGORIZED,
            cloudProvider = null,
            pageCount = pageCount
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
