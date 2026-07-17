package com.propdf.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompressionHistoryDao {
    
    @Insert
    suspend fun insert(entry: CompressionHistoryEntity): Long

    @Query("SELECT * FROM compression_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<CompressionHistoryEntity>>

    @Query("SELECT SUM(originalSizeBytes - compressedSizeBytes) FROM compression_history")
    fun getTotalSpaceSaved(): Flow<Long?>

    @Query("DELETE FROM compression_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: java.util.Date): Int

    @Query("SELECT AVG(compressionRatio) FROM compression_history")
    fun getAverageCompressionRatio(): Flow<Float?>
}
