package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDocumentDao {

    // ── Observe / Query flows ────────────────────────────────────────────

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastOpened DESC")
    fun getRecentFiles(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastModified DESC")
    fun getAllDocumentsFlow(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastModified DESC")
    fun observeAllDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isFavorite = 1 AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getFavorites(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isScanned = 1 AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getScannedDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getDeletedFiles(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE folderId = :folderId AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getFilesInFolder(folderId: Long): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE category = :category AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getFilesByCategory(category: String): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents
        WHERE isDeleted = 0
          AND (fileName LIKE '%' || :query || '%'
               OR displayName LIKE '%' || :query || '%'
               OR searchText LIKE '%' || :query || '%')
        ORDER BY
            CASE WHEN :sortBy = 'name' THEN fileName END ASC,
            CASE WHEN :sortBy = 'date' THEN lastModified END DESC,
            CASE WHEN :sortBy = 'size' THEN fileSize END DESC,
            lastModified DESC
    """)
    fun searchFiles(query: String, sortBy: String = "date"): Flow<List<PdfDocumentEntity>>

    @Query("SELECT DISTINCT category FROM pdf_documents WHERE isDeleted = 0 ORDER BY category")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM pdf_documents WHERE isDeleted = 0")
    fun getDocumentCount(): Flow<Int>

    // ── Suspend queries ──────────────────────────────────────────────────

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getById(id: Long): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getByIdString(id: String): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastModified DESC")
    suspend fun getAllDocuments(): List<PdfDocumentEntity>

    @Query("SELECT SUM(fileSize) FROM pdf_documents WHERE isDeleted = 0")
    suspend fun getTotalStorageSize(): Long?

    // ── Insert / Update ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: PdfDocumentEntity): Long

    @Update
    suspend fun update(document: PdfDocumentEntity)

    // ── Single-field updates ─────────────────────────────────────────────

    @Query("UPDATE pdf_documents SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE pdf_documents SET lastOpened = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pdf_documents SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: String)

    @Query("UPDATE pdf_documents SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?)

    @Query("UPDATE pdf_documents SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: String)

    // ── Recycle bin ──────────────────────────────────────────────────────

    @Query("UPDATE pdf_documents SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id")
    suspend fun moveToRecycleBin(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pdf_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromRecycleBin(id: Long)

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun permanentDelete(id: Long)

    @Query("DELETE FROM pdf_documents WHERE isDeleted = 1 AND deletedAt < :cutoff")
    suspend fun permanentDeleteOld(cutoff: Long)

    // ── Legacy string-id overloads (for existing callers that pass String) ───

    @Query("UPDATE pdf_documents SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE pdf_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM pdf_documents WHERE fileName LIKE '%' || :query || '%' AND isDeleted = 0")
    suspend fun searchByName(query: String): List<PdfDocumentEntity>
}
