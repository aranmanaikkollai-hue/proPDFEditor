package com.propdfeditor.ui.filemanager

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.usecase.GetRecentFilesUseCase
import com.propdf.core.domain.usecase.OpenDocumentUseCase
import com.propdf.core.domain.repository.RecentFileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val getRecentFiles: GetRecentFilesUseCase,
    private val openDocument: OpenDocumentUseCase,
    private val recentFileRepository: RecentFileRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<FileManagerUiState>(FileManagerUiState.Loading)
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private var currentSort = FileSort.RECENTLY_OPENED
    private var currentQuery = ""
    private var favoritesOnly = false

    /** Called once from FileManagerScreen's initial composition when opened in
     *  favorites-only mode (Home's "Favorites" quick action), before the first
     *  loadRecentFiles() call. */
    fun setFavoritesOnly(enabled: Boolean) {
        favoritesOnly = enabled
    }

    fun loadRecentFiles() {
        viewModelScope.launch {
            _uiState.value = FileManagerUiState.Loading
            getRecentFiles(limit = 100)
                .collect { files ->
                    val scoped = if (favoritesOnly) files.filter { it.isFavorite } else files
                    val sorted = sortFiles(scoped, currentSort)
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
        viewModelScope.launch {
            _uiState.value = FileManagerUiState.Loading
            try {
                val folder = DocumentFile.fromTreeUri(context, uri)
                val pdfFiles = folder?.listFiles()
                    ?.filter { doc ->
                        doc.isFile &&
                            (doc.type == "application/pdf" ||
                                doc.name?.endsWith(".pdf", ignoreCase = true) == true)
                    }
                    ?.map { doc ->
                        RecentFile(
                            uri = doc.uri.toString(),
                            name = doc.name ?: "Untitled.pdf",
                            size = doc.length(),
                            lastOpened = doc.lastModified()
                        )
                    }
                    ?: emptyList()
                val sorted = sortFiles(pdfFiles, currentSort)
                val filtered = filterFiles(sorted, currentQuery)
                _uiState.value = if (filtered.isEmpty()) {
                    FileManagerUiState.Empty
                } else {
                    FileManagerUiState.Success(filtered)
                }
            } catch (e: Exception) {
                _uiState.value = FileManagerUiState.Error("Couldn't read folder: ${e.message}")
            }
        }
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

    fun favoriteFile(uri: String, favorite: Boolean) {
        viewModelScope.launch {
            recentFileRepository.favoriteFile(uri, favorite)
        }
    }

    fun deleteFile(uri: String) {
        viewModelScope.launch {
            recentFileRepository.deleteFile(uri)
        }
    }

    /**
     * Clears recent-history entries only (pinned/favorited files are kept,
     * and nothing on disk is touched -- see RecentFileRepository.
     * clearRecentHistory()). The list refreshes immediately afterward since
     * loadRecentFiles() re-collects from the same repository.
     */
    fun clearRecentFiles() {
        viewModelScope.launch {
            recentFileRepository.clearRecentHistory()
            loadRecentFiles()
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
