package com.propdf.scanner.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedDocumentDao {
    @Query("SELECT * FROM scanned_documents ORDER BY updatedAt DESC")
    fun getAllDocuments(): Flow<List<ScannedDocumentEntity>>

    @Query("SELECT * FROM scanned_documents WHERE id = :id")
    suspend fun getDocumentById(id: String): ScannedDocumentEntity?

    @Query("SELECT * FROM scanned_documents WHERE id = :id")
    fun getDocumentByIdFlow(id: String): Flow<ScannedDocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: ScannedDocumentEntity)

    @Update
    suspend fun updateDocument(document: ScannedDocumentEntity)

    @Delete
    suspend fun deleteDocument(document: ScannedDocumentEntity)

    @Query("DELETE FROM scanned_documents WHERE id = :id")
    suspend fun deleteDocumentById(id: String)

    @Query("SELECT * FROM scanned_pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: String): List<ScannedPageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: ScannedPageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<ScannedPageEntity>)

    @Delete
    suspend fun deletePage(page: ScannedPageEntity)

    @Query("DELETE FROM scanned_pages WHERE documentId = :documentId")
    suspend fun deletePagesForDocument(documentId: String)

    @Query("SELECT COUNT(*) FROM scanned_documents")
    suspend fun getDocumentCount(): Int

    @Query("SELECT * FROM scanned_documents WHERE name LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchDocuments(query: String): Flow<List<ScannedDocumentEntity>>
}
