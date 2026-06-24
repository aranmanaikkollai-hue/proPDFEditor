package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDocumentDao {

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastModified DESC")
    fun getAllDocuments(): List<PdfDocumentEntity>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastModified DESC")
    fun observeAllDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getById(id: String): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE isFavorite = 1 AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getFavorites(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isScanned = 1 AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getScannedDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getDeletedDocuments(): Flow<List<PdfDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: PdfDocumentEntity)

    @Update
    suspend fun update(document: PdfDocumentEntity)

    @Query("UPDATE pdf_documents SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE pdf_documents SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long)

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE pdf_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM pdf_documents WHERE fileName LIKE '%' || :query || '%' AND isDeleted = 0")
    suspend fun searchByName(query: String): List<PdfDocumentEntity>

    @Query("SELECT COUNT(*) FROM pdf_documents WHERE isDeleted = 0")
    suspend fun getDocumentCount(): Int

    @Query("SELECT SUM(fileSize) FROM pdf_documents WHERE isDeleted = 0")
    suspend fun getTotalStorageSize(): Long?
}
