package com.propdf.core.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: OcrJobEntity)

    @Update
    suspend fun update(job: OcrJobEntity)

    @Delete
    suspend fun delete(job: OcrJobEntity)

    @Query("SELECT * FROM ocr_jobs WHERE id = :id")
    suspend fun getById(id: String): OcrJobEntity?

    @Query("SELECT * FROM ocr_jobs ORDER BY createdAt DESC")
    fun getAll(): Flow<List<OcrJobEntity>>

    @Query("SELECT * FROM ocr_jobs WHERE status IN ('PENDING', 'DOWNLOADING_MODELS', 'PREPROCESSING', 'RECOGNIZING', 'CORRECTING', 'EXPORTING') ORDER BY createdAt DESC")
    fun getActiveJobs(): Flow<List<OcrJobEntity>>

    @Query("SELECT * FROM ocr_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY createdAt DESC")
    fun getCompletedJobs(): Flow<List<OcrJobEntity>>

    @Query("DELETE FROM ocr_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND createdAt < :olderThan")
    suspend fun deleteOldCompleted(olderThan: Long): Int

    @Query("UPDATE ocr_jobs SET status = 'CANCELLED' WHERE status IN ('PENDING', 'DOWNLOADING_MODELS', 'PREPROCESSING', 'RECOGNIZING', 'CORRECTING', 'EXPORTING')")
    suspend fun cancelAllActive()
}
