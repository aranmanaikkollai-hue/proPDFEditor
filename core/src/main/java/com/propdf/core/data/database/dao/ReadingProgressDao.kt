package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress ORDER BY lastReadAt DESC LIMIT 1")
    fun observeLatest(): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE documentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(progress: ReadingProgressEntity)
}
