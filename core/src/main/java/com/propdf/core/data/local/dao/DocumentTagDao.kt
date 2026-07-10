package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.DocumentTagCrossRef
import com.propdf.core.data.entity.DocumentTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentTagDao {

    @Query("SELECT * FROM document_tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<DocumentTagEntity>>

    @Query("""
        SELECT t.* FROM document_tags t
        INNER JOIN document_tag_cross_ref ref ON t.id = ref.tag_id
        WHERE ref.document_id = :documentId
    """)
    fun getTagsForDocument(documentId: Long): Flow<List<DocumentTagEntity>>

    @Query("""
        SELECT d.* FROM pdf_documents d
        INNER JOIN document_tag_cross_ref ref ON d.id = ref.document_id
        WHERE ref.tag_id = :tagId AND d.is_in_recycle_bin = 0
    """)
    fun getDocumentsByTag(tagId: Long): Flow<List<com.propdf.core.data.entity.PdfDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: DocumentTagEntity): Long

    @Delete
    suspend fun deleteTag(tag: DocumentTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToDocument(crossRef: DocumentTagCrossRef)

    @Query("DELETE FROM document_tag_cross_ref WHERE document_id = :documentId AND tag_id = :tagId")
    suspend fun removeTagFromDocument(documentId: Long, tagId: Long)

    @Query("DELETE FROM document_tag_cross_ref WHERE document_id = :documentId")
    suspend fun removeAllTagsFromDocument(documentId: Long)

    @Query("SELECT COUNT(*) FROM document_tag_cross_ref WHERE tag_id = :tagId")
    suspend fun getTagDocumentCount(tagId: Long): Int
}
