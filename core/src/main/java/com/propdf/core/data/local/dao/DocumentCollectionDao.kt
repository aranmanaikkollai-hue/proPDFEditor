package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.DocumentCollectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentCollectionDao {

    @Query("SELECT * FROM document_collections ORDER BY created_at DESC")
    fun getAllCollections(): Flow<List<DocumentCollectionEntity>>

    @Query("SELECT * FROM document_collections WHERE id = :id")
    suspend fun getById(id: Long): DocumentCollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: DocumentCollectionEntity): Long

    @Update
    suspend fun update(collection: DocumentCollectionEntity)

    @Delete
    suspend fun delete(collection: DocumentCollectionEntity)

    @Query("""
        SELECT COUNT(*) FROM pdf_documents 
        WHERE collection_id = :collectionId AND is_in_recycle_bin = 0
    """)
    suspend fun getDocumentCount(collectionId: Long): Int
}
