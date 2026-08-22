package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.SignatureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SignatureEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signature: SignatureEntity)

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE signatures SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)
}
