package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.DocumentTagCrossRef
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import com.propdf.editor.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    @Query("SELECT * FROM tags ORDER BY usageCount DESC, name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun tagDocument(crossRef: DocumentTagCrossRef)

    @Query("DELETE FROM document_tag_cross_ref WHERE documentId = :documentId AND tagId = :tagId")
    suspend fun untagDocument(documentId: String, tagId: String)

    @Transaction
    @Query("""
        SELECT pd.* FROM pdf_documents pd
        INNER JOIN document_tag_cross_ref dtc ON pd.id = dtc.documentId
        WHERE dtc.tagId = :tagId
        ORDER BY pd.lastModified DESC
    """)
    fun getDocumentsByTag(tagId: String): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN document_tag_cross_ref dtc ON t.id = dtc.tagId
        WHERE dtc.documentId = :documentId
    """)
    fun getTagsForDocument(documentId: String): Flow<List<TagEntity>>

    @Query("UPDATE tags SET usageCount = (SELECT COUNT(*) FROM document_tag_cross_ref WHERE tagId = :tagId) WHERE id = :tagId")
    suspend fun updateUsageCount(tagId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM document_tag_cross_ref WHERE documentId = :documentId AND tagId = :tagId)")
    suspend fun isDocumentTagged(documentId: String, tagId: String): Boolean
}
