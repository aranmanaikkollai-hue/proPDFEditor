package com.propdf.viewer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPageDao {

    @Query("SELECT * FROM recent_pages WHERE documentUri = :documentUri ORDER BY lastViewed DESC LIMIT 20")
    fun getRecentPagesForDocument(documentUri: String): Flow<List<RecentPageEntity>>

    @Query("SELECT * FROM recent_pages WHERE documentUri = :documentUri ORDER BY lastViewed DESC LIMIT 20")
    suspend fun getRecentPagesForDocumentSync(documentUri: String): List<RecentPageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentPage(recentPage: RecentPageEntity)

    @Query("UPDATE recent_pages SET viewCount = viewCount + 1, lastViewed = :timestamp WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun incrementViewCount(documentUri: String, pageIndex: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM recent_pages WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteRecentPage(documentUri: String, pageIndex: Int)
}
