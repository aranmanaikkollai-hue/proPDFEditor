package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDocumentDao {

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY 
            CASE WHEN :sort = 'NAME_ASC' THEN file_name END ASC,
            CASE WHEN :sort = 'NAME_DESC' THEN file_name END DESC,
            CASE WHEN :sort = 'DATE_MODIFIED_ASC' THEN last_modified END ASC,
            CASE WHEN :sort = 'DATE_MODIFIED_DESC' THEN last_modified END DESC,
            CASE WHEN :sort = 'DATE_OPENED_ASC' THEN last_opened END ASC,
            CASE WHEN :sort = 'DATE_OPENED_DESC' THEN COALESCE(last_opened, 0) END DESC,
            CASE WHEN :sort = 'SIZE_ASC' THEN size_bytes END ASC,
            CASE WHEN :sort = 'SIZE_DESC' THEN size_bytes END DESC,
            CASE WHEN :sort = 'PAGE_COUNT_ASC' THEN page_count END ASC,
            CASE WHEN :sort = 'PAGE_COUNT_DESC' THEN page_count END DESC
    """)
    fun getAllDocuments(sort: String, includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND is_favorite = 1
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY last_modified DESC
    """)
    fun getFavoriteDocuments(includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND last_opened IS NOT NULL
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY last_opened DESC 
        LIMIT :limit
    """)
    fun getRecentDocuments(limit: Int, includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 1 
        ORDER BY deleted_at DESC
    """)
    fun getRecycleBinDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND size_bytes > :threshold
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY size_bytes DESC
    """)
    fun getLargeFiles(threshold: Long, includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND file_path LIKE :folderPath || '%'
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY 
            CASE 
                WHEN file_path = :folderPath || file_name THEN 0 
                ELSE 1 
            END,
            last_modified DESC
    """)
    fun getDocumentsInFolder(folderPath: String, includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("""
        SELECT * FROM pdf_documents 
        WHERE is_in_recycle_bin = 0 
        AND (
            file_name LIKE '%' || :query || '%' 
            OR metadata_title LIKE '%' || :query || '%'
            OR metadata_author LIKE '%' || :query || '%'
            OR metadata_subject LIKE '%' || :query || '%'
            OR metadata_keywords LIKE '%' || :query || '%'
        )
        AND (:includeHidden = 1 OR is_hidden = 0)
        ORDER BY 
            CASE 
                WHEN file_name LIKE :query || '%' THEN 0
                WHEN metadata_title LIKE :query || '%' THEN 1
                ELSE 2
            END,
            last_modified DESC
    """)
    fun searchDocuments(query: String, includeHidden: Boolean): Flow<List<PdfDocumentEntity>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getById(id: Long): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents WHERE uri_string = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): PdfDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: PdfDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<PdfDocumentEntity>)

    @Update
    suspend fun update(document: PdfDocumentEntity)

    @Query("UPDATE pdf_documents SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE pdf_documents SET is_hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE pdf_documents SET last_opened = :timestamp WHERE id = :id")
    suspend fun updateLastOpened(id: Long, timestamp: Long)

    @Query("UPDATE pdf_documents SET is_in_recycle_bin = 1, deleted_at = :timestamp WHERE id = :id")
    suspend fun moveToRecycleBin(id: Long, timestamp: Long)

    @Query("UPDATE pdf_documents SET is_in_recycle_bin = 0, deleted_at = NULL WHERE id = :id")
    suspend fun restoreFromRecycleBin(id: Long)

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("DELETE FROM pdf_documents WHERE is_in_recycle_bin = 1 AND deleted_at < :cutoffTime")
    suspend fun deleteOldRecycleBinItems(cutoffTime: Long)

    @Query("SELECT * FROM pdf_documents WHERE checksum IS NOT NULL GROUP BY checksum HAVING COUNT(*) > 1")
    suspend fun findPotentialDuplicates(): List<PdfDocumentEntity>

    @Query("SELECT * FROM pdf_documents WHERE checksum = :checksum")
    suspend fun getByChecksum(checksum: String): List<PdfDocumentEntity>

    @Query("SELECT COUNT(*) FROM pdf_documents WHERE is_in_recycle_bin = 0")
    fun getDocumentCount(): Flow<Int>

    @Query("SELECT SUM(size_bytes) FROM pdf_documents WHERE is_in_recycle_bin = 0")
    fun getTotalSize(): Flow<Long?>

    @Query("SELECT * FROM pdf_documents WHERE is_in_recycle_bin = 0 ORDER BY size_bytes DESC LIMIT :limit")
    suspend fun getLargestFiles(limit: Int): List<PdfDocumentEntity>

    @Query("""
        SELECT DISTINCT SUBSTR(file_path, 1, LENGTH(file_path) - LENGTH(file_name) - 1) as folder 
        FROM pdf_documents 
        WHERE is_in_recycle_bin = 0
    """)
    suspend fun getAllFolders(): List<String>

    @Query("UPDATE pdf_documents SET file_path = :newPath, file_name = :newName WHERE id = :id")
    suspend fun updatePathAndName(id: Long, newPath: String, newName: String)

    @Query("UPDATE pdf_documents SET collection_id = :collectionId WHERE id = :id")
    suspend fun updateCollection(id: Long, collectionId: Long?)

    @Query("SELECT * FROM pdf_documents WHERE collection_id = :collectionId AND is_in_recycle_bin = 0")
    fun getDocumentsByCollection(collectionId: Long): Flow<List<PdfDocumentEntity>>

    @Query("SELECT COUNT(*) FROM pdf_documents WHERE is_in_recycle_bin = 0 AND is_hidden = 0")
    suspend fun getVisibleDocumentCount(): Int
}
