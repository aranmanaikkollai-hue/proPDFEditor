package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.IndexingStatus
import com.propdf.editor.data.local.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: SearchIndexEntity)

    @Query("""
        SELECT * FROM search_index 
        WHERE fileName LIKE '%' || :query || '%' 
           OR contentText LIKE '%' || :query || '%'
           OR ocrText LIKE '%' || :query || '%'
        ORDER BY 
            CASE WHEN fileName LIKE :query || '%' THEN 0 ELSE 1 END,
            lastAccessed DESC
    """)
    suspend fun search(query: String): List<SearchIndexEntity>

    @Query("SELECT * FROM search_index WHERE ocrText LIKE '%' || :query || '%'")
    suspend fun searchOcrOnly(query: String): List<SearchIndexEntity>

    @Query("SELECT * FROM search_index WHERE documentId = :documentId")
    suspend fun getByDocumentId(documentId: String): SearchIndexEntity?

    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("SELECT * FROM search_index WHERE indexingStatus = :status")
    fun getByStatus(status: IndexingStatus): Flow<List<SearchIndexEntity>>

    @Query("""
        SELECT * FROM search_index 
        WHERE indexingStatus IN (:statuses)
        ORDER BY indexedAt ASC
        LIMIT :limit
    """)
    suspend fun getPendingForProcessing(
        statuses: List<IndexingStatus> = listOf(IndexingStatus.PENDING, IndexingStatus.FAILED),
        limit: Int = 10
    ): List<SearchIndexEntity>

    @Query("""
        UPDATE search_index 
        SET ocrText = :ocrText, indexingStatus = :status, indexedAt = :timestamp 
        WHERE documentId = :documentId
    """)
    suspend fun updateOcrResult(
        documentId: String,
        ocrText: String?,
        status: IndexingStatus,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE search_index SET indexingStatus = :status WHERE documentId = :documentId")
    suspend fun updateStatus(documentId: String, status: IndexingStatus)

    @Query("SELECT COUNT(*) FROM search_index WHERE indexingStatus = :status")
    suspend fun countByStatus(status: IndexingStatus): Int

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun getTotalIndexedCount(): Int

    @Query("""
        SELECT si.* FROM search_index si
        INNER JOIN pdf_documents pd ON si.documentId = pd.id
        WHERE pd.isScanned = 1 AND si.indexingStatus != 'COMPLETED'
    """)
    suspend fun getUnindexedScannedDocuments(): List<SearchIndexEntity>

    @Query("UPDATE search_index SET lastAccessed = :timestamp WHERE documentId = :documentId")
    suspend fun updateLastAccessed(documentId: String, timestamp: Long = System.currentTimeMillis())
}
