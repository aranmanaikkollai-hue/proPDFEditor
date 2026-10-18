package com.propdfeditor.batch.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: BatchJobEntity): Long

    @Update
    suspend fun update(job: BatchJobEntity)

    @Delete
    suspend fun delete(job: BatchJobEntity)

    @Query("SELECT * FROM batch_jobs WHERE id = :id")
    suspend fun getById(id: Long): BatchJobEntity?

    @Query("SELECT * FROM batch_jobs ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BatchJobEntity>>

    @Query("SELECT * FROM batch_jobs WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatus(status: BatchJobStatus): Flow<List<BatchJobEntity>>

    @Query("SELECT * FROM batch_jobs WHERE status IN ('RUNNING', 'PENDING') ORDER BY createdAt ASC")
    fun getActiveJobs(): Flow<List<BatchJobEntity>>

    @Query("UPDATE batch_jobs SET status = 'CANCELLED' WHERE id = :id")
    suspend fun cancelJob(id: Long)

    @Query("DELETE FROM batch_jobs WHERE status = 'COMPLETED' AND completedAt < :timestamp")
    suspend fun deleteOldCompletedJobs(timestamp: Long)

    @Query("SELECT COUNT(*) FROM batch_jobs WHERE status = 'RUNNING'")
    suspend fun getRunningJobCount(): Int

    @Query("UPDATE batch_jobs SET progress = :progress, processedItems = :processed WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, processed: Int)

    @Query("UPDATE batch_jobs SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: BatchJobStatus, error: String? = null)

    @Query("UPDATE batch_jobs SET configJson = :configJson WHERE id = :id")
    suspend fun updateConfigJson(id: Long, configJson: String)

    /**
     * Compare-and-set: writes only if the stored configuration still equals [expectedJson].
     * Returns the number of rows changed (0 = the row was modified concurrently or deleted).
     */
    @Query("UPDATE batch_jobs SET configJson = :newJson WHERE id = :id AND configJson = :expectedJson")
    suspend fun updateConfigJsonIfUnchanged(id: Long, expectedJson: String, newJson: String): Int

    @Query("UPDATE batch_jobs SET workRequestId = :workRequestId WHERE id = :id")
    suspend fun updateWorkRequestId(id: Long, workRequestId: String?)

    @Query("DELETE FROM batch_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Candidate rows for the one-time plaintext-secret migration (LIKE is case-insensitive for ASCII). */
    @Query("SELECT * FROM batch_jobs WHERE configJson LIKE '%password%'")
    suspend fun getRowsMentioningPassword(): List<BatchJobEntity>
}
