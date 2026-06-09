package com.propdf.core.data.database

import androidx.room.*
import com.propdf.core.data.entity.RecentSearchEntity
import com.propdf.core.data.entity.SearchIndexContent
import com.propdf.core.data.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for PDF search indexing and recent search history.
 * Uses FTS4 MATCH for fast full-text search queries.
 */
@Dao
interface SearchDao {

    // --- FTS4 Search Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIndexBatch(entities: List<SearchIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContentBatch(entities: List<SearchIndexContent>)

    /**
     * Full-text search using FTS4 MATCH.
     */
    @Query("""
        SELECT * FROM search_index_content 
        WHERE document_id = :documentId 
        AND rowid IN (
            SELECT rowid FROM search_index 
            WHERE search_index MATCH :query
        )
    """)
    suspend fun searchFts(documentId: String, query: String): List<SearchIndexContent>

    @Query("DELETE FROM search_index_content WHERE document_id = :documentId")
    suspend fun deleteDocumentIndex(documentId: String)

    @Query("DELETE FROM search_index")
    suspend fun clearAllIndex()

    // --- Recent Search Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentSearch(entity: RecentSearchEntity)

    /**
     * Returns distinct recent search queries as plain strings.
     * Room cannot map partial columns to an Entity, so we use a simple String return.
     */
    @Query("""
        SELECT DISTINCT query FROM recent_searches 
        WHERE document_id = :documentId 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getRecentSearches(documentId: String, limit: Int): List<String>

    @Query("DELETE FROM recent_searches WHERE document_id = :documentId")
    suspend fun clearRecentSearches(documentId: String)

    @Query("SELECT COUNT(*) FROM search_index_content WHERE document_id = :documentId")
    suspend fun getIndexedPageCount(documentId: String): Int

    @Query("SELECT COUNT(*) FROM search_index_content")
    fun getTotalIndexedCount(): Flow<Int>
}
