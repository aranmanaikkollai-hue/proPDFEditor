package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.RecycleBinEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecycleBinDao {
    @Query("SELECT * FROM recycle_bin ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<RecycleBinEntity>>

    @Query("SELECT COUNT(*) FROM recycle_bin")
    suspend fun count(): Int

    @Insert
    suspend fun insert(item: RecycleBinEntity)

    @Query("DELETE FROM recycle_bin WHERE originalUri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recycle_bin WHERE willBePermanentlyDeletedAt < :now")
    suspend fun deleteExpired(now: Long)
}
