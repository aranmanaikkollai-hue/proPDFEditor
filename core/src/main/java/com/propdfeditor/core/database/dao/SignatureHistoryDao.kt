package com.propdfeditor.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.propdfeditor.core.database.entity.SignatureHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureHistoryDao {
    @Query("SELECT * FROM signature_history ORDER BY signed_at DESC")
    fun getAllHistory(): Flow<List<SignatureHistoryEntity>>

    @Query("SELECT * FROM signature_history WHERE document_path = :documentPath ORDER BY signed_at DESC")
    fun getHistoryForDocument(documentPath: String): Flow<List<SignatureHistoryEntity>>

    @Query("SELECT * FROM signature_history WHERE signature_id = :signatureId ORDER BY signed_at DESC")
    fun getHistoryForSignature(signatureId: Long): Flow<List<SignatureHistoryEntity>>

    @Query("SELECT * FROM signature_history WHERE id = :id LIMIT 1")
    suspend fun getHistoryById(id: Long): SignatureHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: SignatureHistoryEntity): Long

    @Update
    suspend fun updateHistory(history: SignatureHistoryEntity)

    @Delete
    suspend fun deleteHistory(history: SignatureHistoryEntity)

    @Query("DELETE FROM signature_history WHERE signed_at < :date")
    suspend fun deleteHistoryOlderThan(date: Long)

    @Query("SELECT COUNT(*) FROM signature_history")
    suspend fun getHistoryCount(): Int

    @Query("UPDATE signature_history SET is_verified = :verified, verification_status = :status WHERE id = :id")
    suspend fun updateVerificationStatus(id: Long, verified: Boolean, status: SignatureHistoryEntity.VerificationStatus)
}
