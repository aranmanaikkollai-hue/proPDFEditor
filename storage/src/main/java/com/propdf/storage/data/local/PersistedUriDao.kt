package com.propdf.storage.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.propdf.storage.data.local.entity.PersistedUriEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersistedUriDao {
    @Query("SELECT * FROM persisted_uris ORDER BY added_at DESC")
    fun getAll(): Flow<List<PersistedUriEntity>>

    @Query("SELECT * FROM persisted_uris WHERE id = :id")
    suspend fun getById(id: Long): PersistedUriEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PersistedUriEntity): Long

    @Delete
    suspend fun delete(entity: PersistedUriEntity)

    @Query("DELETE FROM persisted_uris WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM persisted_uris WHERE uri_string = :uriString")
    suspend fun countByUri(uriString: String): Int
}
