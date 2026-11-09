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

    /**
     * Read-only lookup with an explicit FOUND / NOT_FOUND / LOOKUP_FAILED result. A failed lookup is
     * never reported as NOT_FOUND. (Replaces the former nullable getRecentFileByUri, which turned
     * every error into "missing".)
     */
    suspend fun lookupRecentFile(uri: String): com.propdf.core.domain.repository.RecentFileLookup

    /** Inserts only when no entry exists for the URI; never replaces or modifies an existing entry. */
    suspend fun insertRecentFileIfAbsent(
        recentFile: com.propdf.core.domain.model.RecentFile
    ): com.propdf.core.domain.repository.RecentFileInsertResult
}
