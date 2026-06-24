package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.CollectionEntity
import com.propdf.editor.data.local.entity.DocumentCollectionCrossRef
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Delete
    suspend fun deleteCollection(collection: CollectionEntity)

    @Query("SELECT * FROM collections WHERE parentId IS NULL ORDER BY sortOrder, name")
    fun getRootCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE parentId = :parentId ORDER BY sortOrder, name")
    fun getChildCollections(parentId: String): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE isSmartFolder = 1")
    fun getSmartFolders(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addDocumentToCollection(crossRef: DocumentCollectionCrossRef)

    @Query("DELETE FROM document_collection_cross_ref WHERE documentId = :documentId AND collectionId = :collectionId")
    suspend fun removeDocumentFromCollection(documentId: String, collectionId: String)

    @Query("DELETE FROM document_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun clearCollection(collectionId: String)

    @Transaction
    @Query("""
        SELECT pd.* FROM pdf_documents pd
        INNER JOIN document_collection_cross_ref dcc ON pd.id = dcc.documentId
        WHERE dcc.collectionId = :collectionId
        ORDER BY pd.lastModified DESC
    """)
    fun getDocumentsInCollection(collectionId: String): Flow<List<PdfDocumentEntity>>

    @Query("SELECT COUNT(*) FROM document_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun getDocumentCount(collectionId: String): Int

    @Query("UPDATE collections SET sortOrder = :order WHERE id = :collectionId")
    suspend fun updateSortOrder(collectionId: String, order: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM document_collection_cross_ref WHERE documentId = :documentId AND collectionId = :collectionId)")
    suspend fun isDocumentInCollection(documentId: String, collectionId: String): Boolean
}
