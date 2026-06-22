package com.propdf.nas.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.propdf.nas.data.local.entity.NasConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NasConfigDao {
    @Query("SELECT * FROM nas_configs ORDER BY display_name ASC")
    fun getAll(): Flow<List<NasConfigEntity>>

    @Query("SELECT * FROM nas_configs WHERE id = :id")
    suspend fun getById(id: Long): NasConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NasConfigEntity): Long

    @Delete
    suspend fun delete(entity: NasConfigEntity)

    @Query("DELETE FROM nas_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
