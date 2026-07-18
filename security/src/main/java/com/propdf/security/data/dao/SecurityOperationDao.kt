// security/src/main/java/com/propdf/security/data/dao/SecurityOperationDao.kt
package com.propdf.security.data.dao

import androidx.room.*
import com.propdf.security.data.entity.SecurityOperationEntity
import com.propdf.security.data.entity.OperationStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SecurityOperationDao {
    @Query("SELECT * FROM security_operations ORDER BY createdAt DESC")
    fun getAllOperations(): Flow<List<SecurityOperationEntity>>

    @Query("SELECT * FROM security_operations WHERE documentUri = :uri ORDER BY createdAt DESC")
    fun getOperationsForDocument(uri: String): Flow<List<SecurityOperationEntity>>

    @Query("SELECT * FROM security_operations WHERE status = :status")
    fun getOperationsByStatus(status: OperationStatus): Flow<List<SecurityOperationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: SecurityOperationEntity): Long

    @Update
    suspend fun update(operation: SecurityOperationEntity)

    @Delete
    suspend fun delete(operation: SecurityOperationEntity)

    @Query("DELETE FROM security_operations WHERE createdAt < :before")
    suspend fun deleteOldOperations(before: java.time.Instant)
}
