package com.propdf.editor.ui.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.*
import com.propdf.core.domain.result.Result
import com.propdf.core.domain.result.safeCall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentManagerUiState(
    val documents: List<PdfDocument> = emptyList(),
    val selectedDocuments: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentView: DocumentViewType = DocumentViewType.ALL,
    val filter: DocumentFilter = DocumentFilter(),
    val isSelectionMode: Boolean = false,
    val sortOption: SortOption = SortOption.DATE_MODIFIED_DESC,
    val searchQuery: String = "",
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val storageAnalysis: StorageAnalysis? = null,
    val recentActivities: List<RecentActivity> = emptyList(),
    val collections: List<DocumentCollection> = emptyList(),
    val tags: List<DocumentTag> = emptyList(),
    val currentFolderPath: String? = null
)

enum class DocumentViewType {
    ALL, RECENT, FAVORITES, COLLECTIONS, TAGS, FOLDER_BROWSER,
    SEARCH, LARGE_FILES, RECYCLE_BIN, DUPLICATE_FINDER,
    STORAGE_ANALYZER, RECENT_ACTIVITY, HIDDEN
}

@HiltViewModel
class DocumentManagerViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val activityRepository: ActivityRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentManagerUiState())
    val uiState: StateFlow<DocumentManagerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DocumentManagerEvent>()
    val events: SharedFlow<DocumentManagerEvent> = _events.asSharedFlow()

    init {
        loadCollections()
        loadTags()
        loadDocuments()
    }

    fun setViewType(viewType: DocumentViewType, folderPath: String? = null) {
        _uiState.update { it.copy(currentView = viewType, currentFolderPath = folderPath) }
        loadDocuments()
    }

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val flow = when (_uiState.value.currentView) {
                    DocumentViewType.ALL -> documentRepository.getAllDocuments(_uiState.value.filter)
                    DocumentViewType.RECENT -> documentRepository.getRecentDocuments(50)
                    DocumentViewType.FAVORITES -> documentRepository.getFavoriteDocuments()
                    DocumentViewType.COLLECTIONS -> {
                        val collectionId = _uiState.value.filter.collections.firstOrNull()
                        if (collectionId != null) {
                            documentRepository.getDocumentsByCollection(collectionId)
                        } else {
                            flowOf(emptyList())
                        }
                    }
                    DocumentViewType.TAGS -> {
                        val tagId = _uiState.value.filter.tags.firstOrNull()
                        if (tagId != null) {
                            documentRepository.getDocumentsByTag(tagId)
                        } else {
                            flowOf(emptyList())
                        }
                    }
                    DocumentViewType.FOLDER_BROWSER -> {
                        _uiState.value.currentFolderPath?.let { path ->
                            documentRepository.getDocumentsInFolder(path)
                        } ?: documentRepository.getAllDocuments(_uiState.value.filter)
                    }
                    DocumentViewType.SEARCH -> {
                        if (_uiState.value.searchQuery.length >= 2) {
                            documentRepository.searchDocuments(_uiState.value.searchQuery)
                        } else {
                            flowOf(emptyList())
                        }
                    }
                    DocumentViewType.LARGE_FILES -> documentRepository.getLargeFiles(10 * 1024 * 1024)
                    DocumentViewType.RECYCLE_BIN -> documentRepository.getRecycleBinDocuments()
                    DocumentViewType.DUPLICATE_FINDER -> flowOf(emptyList())
                    DocumentViewType.STORAGE_ANALYZER -> flowOf(emptyList())
                    DocumentViewType.RECENT_ACTIVITY -> flowOf(emptyList())
                    DocumentViewType.HIDDEN -> documentRepository.getHiddenDocuments()
                }
                
                flow.collect { documents ->
                    _uiState.update { 
                        it.copy(
                            documents = documents,
                            isLoading = false
                        ) 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query, currentView = DocumentViewType.SEARCH) }
        if (query.length >= 2) {
            loadDocuments()
        }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update { 
            it.copy(
                sortOption = option,
                filter = it.filter.copy(sortOption = option)
            ) 
        }
        loadDocuments()
    }

    fun toggleSelection(documentId: Long) {
        _uiState.update { state ->
            val newSelection = if (documentId in state.selectedDocuments) {
                state.selectedDocuments - documentId
            } else {
                state.selectedDocuments + documentId
            }
            state.copy(
                selectedDocuments = newSelection,
                isSelectionMode = newSelection.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val allIds = state.documents.map { it.id }.toSet()
            state.copy(selectedDocuments = allIds, isSelectionMode = true)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedDocuments = emptySet(), isSelectionMode = false) }
    }

    fun toggleFavorite(documentId: Long) {
        viewModelScope.launch {
            val doc = documentRepository.getDocumentById(documentId) ?: return@launch
            documentRepository.setFavorite(documentId, !doc.isFavorite)
            _events.emit(DocumentManagerEvent.ShowSnackbar(
                if (!doc.isFavorite) "Added to favorites" else "Removed from favorites"
            ))
        }
    }

    fun renameDocument(documentId: Long, newName: String) {
        viewModelScope.launch {
            safeCall {
                documentRepository.renameDocument(documentId, newName)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Renamed successfully"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to rename: ${it.message}"))
            }
        }
    }

    fun moveDocument(documentId: Long, destinationPath: String) {
        viewModelScope.launch {
            safeCall {
                documentRepository.moveDocument(documentId, destinationPath)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Moved successfully"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to move: ${it.message}"))
            }
        }
    }

    fun copyDocument(documentId: Long, destinationPath: String) {
        viewModelScope.launch {
            safeCall {
                documentRepository.copyDocument(documentId, destinationPath)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Copied successfully"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to copy: ${it.message}"))
            }
        }
    }

    fun deleteDocument(documentId: Long) {
        viewModelScope.launch {
            safeCall {
                documentRepository.moveToRecycleBin(documentId)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Moved to recycle bin"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to delete: ${it.message}"))
            }
        }
    }

    fun permanentlyDelete(documentId: Long) {
        viewModelScope.launch {
            safeCall {
                documentRepository.permanentlyDelete(documentId)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Permanently deleted"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to delete: ${it.message}"))
            }
        }
    }

    fun restoreDocument(documentId: Long) {
        viewModelScope.launch {
            safeCall {
                documentRepository.restoreFromRecycleBin(documentId)
            }.onSuccess {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Restored successfully"))
                loadDocuments()
            }.onError {
                _events.emit(DocumentManagerEvent.ShowSnackbar("Failed to restore: ${it.message}"))
            }
        }
    }

    fun shareDocument(documentId: Long) {
        viewModelScope.launch {
            val doc = documentRepository.getDocumentById(documentId) ?: return@launch
            _events.emit(DocumentManagerEvent.ShareDocument(doc.uriString))
        }
    }

    fun toggleHidden(documentId: Long) {
        viewModelScope.launch {
            val doc = documentRepository.getDocumentById(documentId) ?: return@launch
            documentRepository.setHidden(documentId, !doc.isHidden)
            _events.emit(DocumentManagerEvent.ShowSnackbar(
                if (!doc.isHidden) "Hidden from view" else "Unhidden"
            ))
            loadDocuments()
        }
    }

    // Batch Operations
    fun batchDelete() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchMoveToRecycleBin(ids)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items moved to recycle bin"))
            loadDocuments()
        }
    }

    fun batchRestore() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchRestore(ids)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items restored"))
            loadDocuments()
        }
    }

    fun batchFavorite(favorite: Boolean) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchFavorite(ids, favorite)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items updated"))
            loadDocuments()
        }
    }

    fun batchMove(destinationPath: String) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchMove(ids, destinationPath)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items moved"))
            loadDocuments()
        }
    }

    fun batchCopy(destinationPath: String) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchCopy(ids, destinationPath)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items copied"))
            loadDocuments()
        }
    }

    fun batchHide(hidden: Boolean) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchHide(ids, hidden)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items ${if (hidden) "hidden" else "unhidden"}"))
            loadDocuments()
        }
    }

    fun batchAddToCollection(collectionId: Long?) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchAddToCollection(ids, collectionId)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("${ids.size} items updated"))
            loadDocuments()
        }
    }

    fun batchAddTags(tagIds: List<Long>) {
        viewModelScope.launch {
            val ids = _uiState.value.selectedDocuments.toList()
            documentRepository.batchAddTags(ids, tagIds)
            clearSelection()
            _events.emit(DocumentManagerEvent.ShowSnackbar("Tags applied to ${ids.size} items"))
            loadDocuments()
        }
    }

    // Duplicate Finder
    fun findDuplicates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val groups = documentRepository.findDuplicates()
            _uiState.update { it.copy(duplicateGroups = groups, isLoading = false) }
        }
    }

    // Storage Analyzer
    fun analyzeStorage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val analysis = documentRepository.getStorageAnalysis()
            _uiState.update { it.copy(storageAnalysis = analysis, isLoading = false) }
        }
    }

    // Recent Activity
    fun loadRecentActivities() {
        viewModelScope.launch {
            activityRepository.getRecentActivities(100).collect { activities ->
                _uiState.update { it.copy(recentActivities = activities) }
            }
        }
    }

    // Collections
    fun loadCollections() {
        viewModelScope.launch {
            collectionRepository.getAllCollections().collect { collections ->
                _uiState.update { it.copy(collections = collections) }
            }
        }
    }

    fun createCollection(name: String, description: String?, color: Int) {
        viewModelScope.launch {
            collectionRepository.createCollection(name, description, color)
        }
    }

    // Tags
    fun loadTags() {
        viewModelScope.launch {
            tagRepository.getAllTags().collect { tags ->
                _uiState.update { it.copy(tags = tags) }
            }
        }
    }

    fun createTag(name: String, color: Int) {
        viewModelScope.launch {
            tagRepository.createTag(name, color)
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            documentRepository.emptyRecycleBin()
            _events.emit(DocumentManagerEvent.ShowSnackbar("Recycle bin emptied"))
            loadDocuments()
        }
    }
}

sealed class DocumentManagerEvent {
    data class ShowSnackbar(val message: String) : DocumentManagerEvent()
    data class ShareDocument(val uriString: String) : DocumentManagerEvent()
    data class NavigateToViewer(val documentId: Long) : DocumentManagerEvent()
    data class ShowError(val message: String) : DocumentManagerEvent()
}
