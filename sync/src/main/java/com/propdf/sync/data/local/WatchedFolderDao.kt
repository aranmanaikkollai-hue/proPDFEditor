package com.propdf.sync.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.propdf.sync.data.local.entity.WatchedFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedFolderDao {
    @Query("SELECT * FROM watched_folders ORDER BY added_at DESC")
    fun getAll(): Flow<List<WatchedFolderEntity>>

    @Query("SELECT * FROM watched_folders WHERE id = :id")
    suspend fun getById(id: Long): WatchedFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WatchedFolderEntity): Long

    @Update
    suspend fun update(entity: WatchedFolderEntity)

    @Delete
    suspend fun delete(entity: WatchedFolderEntity)

    @Query("DELETE FROM watched_folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE watched_folders SET last_checked_at = :timestamp, imported_count = imported_count + :count WHERE id = :id")
    suspend fun updateScanResult(id: Long, timestamp: Long, count: Int)
}
