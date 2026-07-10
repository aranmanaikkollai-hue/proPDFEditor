package com.propdf.core.domain.repository

import com.propdf.core.domain.model.DocumentTag
import com.propdf.core.domain.model.PdfDocument
import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun getAllTags(): Flow<List<DocumentTag>>
    fun getTagsForDocument(documentId: Long): Flow<List<DocumentTag>>
    fun getDocumentsByTag(tagId: Long): Flow<List<PdfDocument>>
    suspend fun createTag(name: String, color: Int): Long
    suspend fun updateTag(tag: DocumentTag)
    suspend fun deleteTag(tagId: Long)
    suspend fun addTagToDocument(documentId: Long, tagId: Long)
    suspend fun removeTagFromDocument(documentId: Long, tagId: Long)
    suspend fun setDocumentTags(documentId: Long, tagIds: List<Long>)
}
