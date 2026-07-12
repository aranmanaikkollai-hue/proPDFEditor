package com.propdf.editor.domain.repository

import com.propdf.editor.domain.model.PdfDocument

interface DocumentRepository {
    suspend fun getAllDocuments(): List<PdfDocument>
    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun deleteDocument(id: Long)
    suspend fun insertOrUpdateRecentFile(recentFile: com.propdf.core.domain.model.RecentFile)
}
