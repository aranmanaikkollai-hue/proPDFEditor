package com.propdf.nas.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.propdf.nas.data.local.entity.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOperationDao {
    @Query("SELECT * FROM pending_nas_operations ORDER BY created_at ASC")
    fun getAll(): Flow<List<PendingOperationEntity>>

    @Query("SELECT * FROM pending_nas_operations ORDER BY created_at ASC")
    suspend fun getAllSync(): List<PendingOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingOperationEntity): Long

    @Delete
    suspend fun delete(entity: PendingOperationEntity)

    @Query("DELETE FROM pending_nas_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_nas_operations SET retry_count = :retryCount WHERE id = :id")
    suspend fun updateRetryCount(id: Long, retryCount: Int)
}
