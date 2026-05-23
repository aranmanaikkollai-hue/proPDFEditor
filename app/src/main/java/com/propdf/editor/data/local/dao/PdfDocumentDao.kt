package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import com.propdf.editor.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDocumentDao {

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 ORDER BY lastOpened DESC")
    fun getRecentFiles(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 AND isFavorite = 1 ORDER BY lastOpened DESC")
    fun getFavorites(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getDeletedFiles(): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 AND folderId = :folderId ORDER BY fileName ASC")
    fun getFilesInFolder(folderId: Long): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE isDeleted = 0 AND category = :category ORDER BY lastOpened DESC")
    fun getFilesByCategory(category: String): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE isDeleted = 0 
        AND (fileName LIKE '%' || :query || '%' OR searchText LIKE '%' || :query || '%')
        ORDER BY 
            CASE WHEN :sortBy = 'name' THEN fileName END ASC,
            CASE WHEN :sortBy = 'date' THEN lastModified END DESC,
            CASE WHEN :sortBy = 'size' THEN fileSize END DESC
    """)
    fun searchFiles(query: String, sortBy: String = "date"): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PdfDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: PdfDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<PdfDocumentEntity>)

    @Update
    suspend fun update(document: PdfDocumentEntity)

    @Query("UPDATE pdf_documents SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE pdf_documents SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id")
    suspend fun moveToRecycleBin(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pdf_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromRecycleBin(id: Long)

    @Query("DELETE FROM pdf_documents WHERE isDeleted = 1 AND deletedAt < :cutoffTime")
    suspend fun permanentDeleteOld(cutoffTime: Long)

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun permanentDelete(id: Long)

    @Query("UPDATE pdf_documents SET lastOpened = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE pdf_documents SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?)

    @Query("UPDATE pdf_documents SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: String)

    @Query("SELECT COUNT(*) FROM pdf_documents WHERE isDeleted = 0")
    fun getDocumentCount(): Flow<Int>

    @Query("SELECT SUM(fileSize) FROM pdf_documents WHERE isDeleted = 0")
    fun getTotalSize(): Flow<Long?>

    @Query("SELECT DISTINCT category FROM pdf_documents WHERE isDeleted = 0")
    fun getAllCategories(): Flow<List<String>>
}
