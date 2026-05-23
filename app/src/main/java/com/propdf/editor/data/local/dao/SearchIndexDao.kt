package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.SearchIndexEntity

@Dao
interface SearchIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SearchIndexEntity>)

    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)

    @Query("""
        SELECT DISTINCT documentId FROM search_index 
        WHERE word LIKE '%' || :query || '%'
        ORDER BY 
            CASE WHEN word = :query THEN 0 ELSE 1 END,
            LENGTH(word)
        LIMIT 100
    """)
    suspend fun searchDocuments(query: String): List<Long>

    @Query("SELECT * FROM search_index WHERE documentId = :documentId AND pageNumber = :page")
    suspend fun getForPage(documentId: Long, page: Int): List<SearchIndexEntity>
}
