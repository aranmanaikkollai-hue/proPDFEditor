package com.propdf.editor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.repository.CollectionRepository
import com.propdf.core.domain.repository.DocumentRepository
import com.propdf.core.domain.repository.TagRepository
import com.propdf.core.domain.model.DocumentCollection
import com.propdf.core.domain.model.DocumentTag
import com.propdf.editor.domain.model.DocumentCategory
import com.propdf.editor.domain.model.Folder
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.StorageStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val recentFiles: List<PdfDocument> = emptyList(),
    val folders: List<Folder> = emptyList(),
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
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val recent = values[0] as List<com.propdf.core.domain.model.PdfDocument>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<DocumentCollection>
                @Suppress("UNCHECKED_CAST")
                val tags = values[2] as List<DocumentTag>
                val count = values[3] as Int
                val totalSize = values[4] as? Long

                // "Folders" on Home is now derived from core's Collection
                // system (see the database consolidation plan) — the old
                // app-local FolderRepository backed this with a table that
                // was empty for anyone without cloud sync configured.
                val folders = collections.map {
                    Folder(
                        id = it.id, name = it.name, color = it.color,
                        icon = "folder", documentCount = it.documentCount,
                        createdAt = it.createdAt, isSystem = false
                    )
                }

                val mappedRecent = recent.map { doc ->
                    PdfDocument(
                        id = doc.id,
                        uri = android.net.Uri.parse(doc.uriString),
                        displayName = doc.displayName,
                        fileSize = doc.sizeBytes,
                        dateModified = doc.lastModified,
                        dateAdded = doc.lastOpened ?: doc.lastModified,
                        isFavorite = doc.isFavorite,
                        isDeleted = doc.isInRecycleBin,
                        category = DocumentCategory.UNCATEGORIZED,
                        cloudProvider = null,
                        pageCount = doc.pageCount
                    )
                }
                val stats = StorageStats(
                    totalDocuments = count,
                    totalSize = totalSize ?: 0,
                    favoriteCount = mappedRecent.count { it.isFavorite },
                    deletedCount = 0 // Would need separate query
                )
                HomeUiState(
                    recentFiles = mappedRecent,
                    folders = folders,
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
