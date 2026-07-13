package com.propdf.editor.domain.repository

import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SortOption
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    suspend fun getAllDocuments(): List<PdfDocument>
    fun getRecentFiles(): Flow<List<PdfDocument>>
    fun getFavorites(): Flow<List<PdfDocument>>
    fun getDeletedFiles(): Flow<List<PdfDocument>>
    fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>>
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun deleteDocument(id: Long)
    suspend fun restoreDocument(id: Long)
    suspend fun permanentDelete(id: Long)
    suspend fun emptyRecycleBin(olderThanDays: Int)
    suspend fun insertOrUpdateRecentFile(recentFile: com.propdf.core.domain.model.RecentFile)
}
