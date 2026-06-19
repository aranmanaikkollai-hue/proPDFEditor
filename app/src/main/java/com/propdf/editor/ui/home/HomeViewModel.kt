package com.propdf.editor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * HomeViewModel — premium dashboard state management.
 * Maps core domain models to local UI models for the home screen.
 * Fixed: uses correct com.propdf.core.domain.model.* package.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recentFilesRepo: RecentFilesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            recentFilesRepo.observeAll().collectLatest { files ->
                val mappedFiles = files.map { it.toPdfDocument() }
                val stats = calculateStats(files)
                val scans = mappedFiles.filter {
                    it.displayName.contains("Scan", ignoreCase = true) ||
                    it.displayName.contains("Camera", ignoreCase = true)
                }

                _uiState.update {
                    HomeUiState(
                        recentFiles = mappedFiles,
                        recentScans = scans,
                        folders = generateSampleFolders(mappedFiles),
                        storageStats = stats,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun toggleFavorite(id: Long, currentState: Boolean) {
        viewModelScope.launch {
            // Implementation depends on RecentFilesRepository API
            // recentFilesRepo.setFavourite(uri, !currentState)
        }
    }

    fun refresh() {
        loadData()
    }

    private fun calculateStats(files: List<RecentFile>): StorageStats {
        val totalSize = files.sumOf { it.fileSizeBytes }
        val favorites = files.count { it.isFavourite }
        val maxStorage = 1024L * 1024L * 1024L // 1GB placeholder
        return StorageStats(
            totalDocuments = files.size,
            totalSize = totalSize,
            favoriteCount = favorites,
            deletedCount = 0,
            storageUsedPercent = (totalSize.toFloat() / maxStorage).coerceIn(0f, 1f)
        )
    }

    private fun generateSampleFolders(files: List<PdfDocument>): List<Folder> {
        if (files.isEmpty()) return emptyList()
        return listOf(
            Folder(1, "Work", 0xFF1E88E5, files.count { it.category == DocumentCategory.WORK }),
            Folder(2, "Personal", 0xFF43A047, files.count { it.category == DocumentCategory.PERSONAL }),
            Folder(3, "Scans", 0xFF00897B, files.count { it.displayName.contains("Scan", ignoreCase = true) })
        ).filter { it.documentCount > 0 }
    }

    private fun RecentFile.toPdfDocument(): PdfDocument {
        return PdfDocument(
            id = uri.hashCode().toLong(),
            uri = uri,
            displayName = displayName,
            fileSize = fileSizeBytes,
            pageCount = pageCount,
            lastOpenedAt = lastOpenedAt,
            isFavorite = isFavourite,
            isDeleted = false,
            category = DocumentCategory.UNCATEGORIZED,
            cloudProvider = null
        )
    }
}

data class HomeUiState(
    val recentFiles: List<PdfDocument> = emptyList(),
    val recentScans: List<PdfDocument> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val storageStats: StorageStats = StorageStats(),
    val isLoading: Boolean = true,
    val error: String? = null
)
