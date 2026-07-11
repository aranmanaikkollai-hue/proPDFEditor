package com.propdf.editor.domain.repository

import com.propdf.editor.domain.model.*
import kotlinx.coroutines.flow.Flow
package com.propdf.editor.domain.repository

import com.propdf.editor.domain.model.PdfDocument

interface DocumentRepository {
    suspend fun getAllDocuments(): List<PdfDocument>
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun deleteDocument(id: Long)
    suspend fun insertOrUpdateRecentFile(recentFile: com.propdf.core.domain.model.RecentFile)
}

interface DocumentRepository {
    fun getRecentFiles(): Flow<List<PdfDocument>>
    fun getFavorites(): Flow<List<PdfDocument>>
    fun getDeletedFiles(): Flow<List<PdfDocument>>
    fun getFilesInFolder(folderId: Long): Flow<List<PdfDocument>>
    fun getFilesByCategory(category: DocumentCategory): Flow<List<PdfDocument>>
    fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>>
    fun getAllCategories(): Flow<List<String>>
    fun getStorageStats(): Flow<StorageStats>

    suspend fun addDocument(document: PdfDocument): Long
    suspend fun updateDocument(document: PdfDocument)
    suspend fun deleteDocument(id: Long)
    suspend fun restoreDocument(id: Long)
    suspend fun permanentDelete(id: Long)
    suspend fun setFavorite(id: Long, isFavorite: Boolean)
    suspend fun moveToFolder(id: Long, folderId: Long?)
    suspend fun moveToRecycleBin(id: Long)
    suspend fun updateLastOpened(id: Long)
    suspend fun setCategory(id: Long, category: DocumentCategory)
    suspend fun setSyncStatus(id: Long, status: SyncStatus)

    suspend fun emptyRecycleBin(olderThan: Long? = null)
    suspend fun cleanupExpiredPermissions()
}
