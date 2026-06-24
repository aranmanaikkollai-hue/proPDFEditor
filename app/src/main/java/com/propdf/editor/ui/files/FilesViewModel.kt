package com.propdf.editor.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.data.local.dao.*
import com.propdf.editor.data.local.entity.*
import com.propdf.editor.domain.usecase.AutoRenameDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val pdfDocumentDao: PdfDocumentDao,
    private val collectionDao: CollectionDao,
    private val tagDao: TagDao,
    private val fileHashDao: FileHashDao,
    private val searchIndexDao: SearchIndexDao,
    private val autoRenameUseCase: AutoRenameDocumentUseCase
) : ViewModel() {

    val collections: StateFlow<List<CollectionEntity>> = collectionDao.getRootCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagEntity>> = tagDao.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val duplicateGroups: StateFlow<Map<String, List<FileHashDao.DuplicateGroupItem>>> = 
        fileHashDao.getAllDuplicateGroups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val indexingProgress: StateFlow<IndexingProgress> = 
        searchIndexDao.getByStatus(IndexingStatus.PROCESSING)
            .map { IndexingProgress(processing = it.size) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IndexingProgress())

    data class IndexingProgress(val processing: Int = 0, val pending: Int = 0, val completed: Int = 0)

    // ── Collection Operations ──────────────────────────────────────────

    fun createCollection(name: String, description: String? = null, parentId: String? = null) {
        viewModelScope.launch {
            val collection = CollectionEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = name.trim(),
                description = description?.trim(),
                parentId = parentId
            )
            collectionDao.insertCollection(collection)
        }
    }

    fun addToCollection(documentId: String, collectionId: String) {
        viewModelScope.launch {
            collectionDao.addDocumentToCollection(
                DocumentCollectionCrossRef(documentId = documentId, collectionId = collectionId)
            )
        }
    }

    fun removeFromCollection(documentId: String, collectionId: String) {
        viewModelScope.launch {
            collectionDao.removeDocumentFromCollection(documentId, collectionId)
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            collectionDao.getById(collectionId)?.let {
                collectionDao.deleteCollection(it)
            }
        }
    }

    fun getDocumentsInCollection(collectionId: String): Flow<List<PdfDocumentEntity>> {
        return collectionDao.getDocumentsInCollection(collectionId)
    }

    // ── Tag Operations ─────────────────────────────────────────────────

    fun createTag(name: String, color: Int? = null) {
        viewModelScope.launch {
            val existing = tagDao.getByName(name.trim())
            if (existing == null) {
                tagDao.insertTag(
                    TagEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        color = color
                    )
                )
            }
        }
    }

    fun tagDocument(documentId: String, tagId: String) {
        viewModelScope.launch {
            tagDao.tagDocument(DocumentTagCrossRef(documentId = documentId, tagId = tagId))
            tagDao.updateUsageCount(tagId)
        }
    }

    fun untagDocument(documentId: String, tagId: String) {
        viewModelScope.launch {
            tagDao.untagDocument(documentId, tagId)
            tagDao.updateUsageCount(tagId)
        }
    }

    fun deleteTag(tag: TagEntity) {
        viewModelScope.launch {
            tagDao.deleteTag(tag)
        }
    }

    fun getTagsForDocument(documentId: String): Flow<List<TagEntity>> {
        return tagDao.getTagsForDocument(documentId)
    }

    // ── Smart Rename ───────────────────────────────────────────────────

    fun smartRename(documentId: String, uri: Uri, currentName: String) {
        viewModelScope.launch {
            val existingNames = pdfDocumentDao.getAllDocuments().map { it.fileName }.toSet()
            autoRenameUseCase(
                AutoRenameDocumentUseCase.Params(
                    documentId = documentId,
                    documentUri = uri,
                    currentFileName = currentName,
                    existingNamesInFolder = existingNames
                )
            )
        }
    }

    // ── Duplicate Actions ──────────────────────────────────────────────

    fun deleteDuplicate(documentId: String) {
        viewModelScope.launch {
            pdfDocumentDao.softDelete(documentId, System.currentTimeMillis())
        }
    }
}
