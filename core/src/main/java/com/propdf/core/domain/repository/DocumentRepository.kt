package com.propdf.core.domain.repository

import com.propdf.core.domain.model.*
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(filter: DocumentFilter): Flow<List<PdfDocument>>
    fun getRecentDocuments(limit: Int = 50): Flow<List<PdfDocument>>
    fun getFavoriteDocuments(): Flow<List<PdfDocument>>
    fun getRecycleBinDocuments(): Flow<List<PdfDocument>>
    fun getLargeFiles(threshold: Long = 50 * 1024 * 1024): Flow<List<PdfDocument>>
    fun searchDocuments(query: String): Flow<List<PdfDocument>>
    fun getDocumentsInFolder(folderPath: String): Flow<List<PdfDocument>>
    fun getDocumentsByCollection(collectionId: Long): Flow<List<PdfDocument>>
    fun getDocumentsByTag(tagId: Long): Flow<List<PdfDocument>>
    
    suspend fun getDocumentById(id: Long): PdfDocument?
    suspend fun insertDocument(document: PdfDocument): Long
    suspend fun updateDocument(document: PdfDocument)
    suspend fun deleteDocument(id: Long)
    suspend fun moveToRecycleBin(id: Long)
    suspend fun restoreFromRecycleBin(id: Long)
    suspend fun permanentlyDelete(id: Long)
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun setHidden(id: Long, hidden: Boolean)
    suspend fun renameDocument(id: Long, newName: String)
    suspend fun moveDocument(id: Long, newPath: String)
    suspend fun copyDocument(id: Long, destinationPath: String): Long
    suspend fun updateLastOpened(id: Long)
    suspend fun addToCollection(documentId: Long, collectionId: Long?)
    
    fun getDocumentCount(): Flow<Int>
    fun getTotalSize(): Flow<Long?>
    suspend fun findDuplicates(): List<DuplicateGroup>
    suspend fun getStorageAnalysis(): StorageAnalysis
    suspend fun emptyRecycleBin()
    suspend fun cleanOldRecycleBinItems(days: Int = 30)
    
    suspend fun batchDelete(ids: List<Long>)
    suspend fun batchMoveToRecycleBin(ids: List<Long>)
    suspend fun batchRestore(ids: List<Long>)
    suspend fun batchFavorite(ids: List<Long>, favorite: Boolean)
    suspend fun batchMove(ids: List<Long>, destinationPath: String)
    suspend fun batchCopy(ids: List<Long>, destinationPath: String)
    suspend fun batchHide(ids: List<Long>, hidden: Boolean)
    suspend fun batchAddToCollection(ids: List<Long>, collectionId: Long?)
    suspend fun batchAddTags(documentIds: List<Long>, tagIds: List<Long>)
}
