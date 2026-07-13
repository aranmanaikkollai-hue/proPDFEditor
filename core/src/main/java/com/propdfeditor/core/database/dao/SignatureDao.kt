package com.propdfeditor.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.propdfeditor.core.database.entity.SignatureEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY use_count DESC, updated_at DESC")
    fun getAllSignatures(): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE type = :type ORDER BY use_count DESC, updated_at DESC")
    fun getSignaturesByType(type: SignatureEntity.SignatureType): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE is_favorite = 1 ORDER BY updated_at DESC")
    fun getFavoriteSignatures(): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE id = :id LIMIT 1")
    suspend fun getSignatureById(id: Long): SignatureEntity?

    @Query("SELECT * FROM signatures WHERE id = :id LIMIT 1")
    fun getSignatureByIdFlow(id: Long): Flow<SignatureEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignature(signature: SignatureEntity): Long

    @Update
    suspend fun updateSignature(signature: SignatureEntity)

    @Delete
    suspend fun deleteSignature(signature: SignatureEntity)

    @Query("UPDATE signatures SET use_count = use_count + 1, updated_at = :date WHERE id = :id")
    suspend fun incrementUseCount(id: Long, date: Long = System.currentTimeMillis())

    @Query("UPDATE signatures SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM signatures")
    suspend fun getSignatureCount(): Int

    @Transaction
    suspend fun insertAndGet(signature: SignatureEntity): SignatureEntity? {
        val id = insertSignature(signature)
        return getSignatureById(id)
    }
}
