package com.propdfeditor.ui.filemanager

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.usecase.GetRecentFilesUseCase
import com.propdf.core.domain.usecase.OpenDocumentUseCase
import com.propdf.core.domain.repository.RecentFileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val getRecentFiles: GetRecentFilesUseCase,
    private val openDocument: OpenDocumentUseCase,
    private val recentFileRepository: RecentFileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Loading)
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private var currentSort = FileSort.RECENTLY_OPENED
    private var currentQuery = ""

    fun loadRecentFiles() {
        viewModelScope.launch {
            _uiState.value = FileManagerUiState.Loading
            getRecentFiles(limit = 100)
                .collect { files ->
                    val sorted = sortFiles(files, currentSort)
                    val filtered = filterFiles(sorted, currentQuery)
                    _uiState.value = if (filtered.isEmpty()) {
                        FileManagerUiState.Empty
                    } else {
                        FileManagerUiState.Success(filtered)
                    }
                }
        }
    }

    fun loadFolder(uri: Uri) {
        // TODO: Implement tree document browsing
        loadRecentFiles()
    }

    fun search(query: String) {
        currentQuery = query
        loadRecentFiles()
    }

    fun setSort(sort: FileSort) {
        currentSort = sort
        loadRecentFiles()
    }

    fun addRecentFile(uri: String) {
        viewModelScope.launch {
            openDocument(Uri.parse(uri))
        }
    }

    fun pinFile(uri: String) {
        viewModelScope.launch {
            recentFileRepository.pinFile(uri, true)
        }
    }

    fun favoriteFile(uri: String) {
        viewModelScope.launch {
            recentFileRepository.favoriteFile(uri, true)
        }
    }

    fun deleteFile(uri: String) {
        viewModelScope.launch {
            recentFileRepository.deleteFile(uri)
        }
    }

    private fun sortFiles(files: List<RecentFile>, sort: FileSort): List<RecentFile> {
        return when (sort) {
            FileSort.NAME -> files.sortedBy { it.name }
            FileSort.DATE -> files.sortedByDescending { it.lastOpened }
            FileSort.SIZE -> files.sortedByDescending { it.size }
            FileSort.RECENTLY_OPENED -> files.sortedByDescending { it.lastOpened }
        }
    }

    private fun filterFiles(files: List<RecentFile>, query: String): List<RecentFile> {
        if (query.isBlank()) return files
        return files.filter { it.name.contains(query, ignoreCase = true) }
    }
}

sealed interface FileManagerUiState {
    data object Loading : FileManagerUiState
    data object Empty : FileManagerUiState
    data class Success(val files: List<RecentFile>) : FileManagerUiState
    data class Error(val message: String) : FileManagerUiState
}
