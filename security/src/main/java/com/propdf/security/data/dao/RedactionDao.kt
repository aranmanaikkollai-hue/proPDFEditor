// security/src/main/java/com/propdf/security/data/dao/RedactionDao.kt
package com.propdf.security.data.dao

import androidx.room.*
import com.propdf.security.data.entity.RedactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RedactionDao {
    @Query("SELECT * FROM redactions WHERE documentUri = :documentUri AND isApplied = 0")
    fun getPendingRedactions(documentUri: String): Flow<List<RedactionEntity>>

    @Query("SELECT * FROM redactions WHERE documentUri = :documentUri")
    fun getAllRedactions(documentUri: String): Flow<List<RedactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(redaction: RedactionEntity): Long

    @Update
    suspend fun update(redaction: RedactionEntity)

    @Delete
    suspend fun delete(redaction: RedactionEntity)

    @Query("DELETE FROM redactions WHERE documentUri = :documentUri AND isApplied = 0")
    suspend fun deletePendingRedactions(documentUri: String)

    @Query("UPDATE redactions SET isApplied = 1 WHERE documentUri = :documentUri AND isApplied = 0")
    suspend fun markAsApplied(documentUri: String)
}
