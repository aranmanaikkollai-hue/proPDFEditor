package com.propdfeditor.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.propdfeditor.core.database.entity.CertificateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CertificateDao {
    @Query("SELECT * FROM certificates ORDER BY created_at DESC")
    fun getAllCertificates(): Flow<List<CertificateEntity>>

    @Query("SELECT * FROM certificates WHERE id = :id LIMIT 1")
    suspend fun getCertificateById(id: Long): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE alias = :alias LIMIT 1")
    suspend fun getCertificateByAlias(alias: String): CertificateEntity?

    @Query("SELECT * FROM certificates WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultCertificate(): CertificateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCertificate(certificate: CertificateEntity): Long

    @Update
    suspend fun updateCertificate(certificate: CertificateEntity)

    @Delete
    suspend fun deleteCertificate(certificate: CertificateEntity)

    @Query("UPDATE certificates SET is_default = 0")
    suspend fun clearDefaultCertificate()

    @Query("UPDATE certificates SET is_default = 1 WHERE id = :id")
    suspend fun setDefaultCertificate(id: Long)

    @Transaction
    suspend fun setAsDefault(id: Long) {
        clearDefaultCertificate()
        setDefaultCertificate(id)
    }

    @Query("UPDATE certificates SET use_count = use_count + 1, last_used_at = :date WHERE id = :id")
    suspend fun incrementUseCount(id: Long, date: Long = System.currentTimeMillis())
}
