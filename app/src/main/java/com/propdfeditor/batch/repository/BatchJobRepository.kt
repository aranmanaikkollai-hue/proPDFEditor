package com.propdfeditor.batch.repository

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import com.propdfeditor.batch.data.database.BatchDatabase
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchJobRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BatchDatabase
) {
    private val dao = database.batchJobDao()

    val allJobs: Flow<List<BatchJobEntity>> = dao.getAll()
    val activeJobs: Flow<List<BatchJobEntity>> = dao.getActiveJobs()

    suspend fun createJob(
        type: BatchJobType,
        inputUris: List<Uri>,
        configJson: String = "{}",
        outputUri: Uri? = null
    ): Long = withContext(Dispatchers.IO) {
        val job = BatchJobEntity(
            type = type,
            inputUris = inputUris,
            outputUri = outputUri,
            configJson = configJson,
            totalItems = inputUris.size
        )
        dao.insert(job)
    }

    suspend fun updateJob(job: BatchJobEntity) = withContext(Dispatchers.IO) {
        dao.update(job)
    }

    suspend fun updateProgress(jobId: Long, progress: Int, processed: Int) = withContext(Dispatchers.IO) {
        dao.updateProgress(jobId, progress, processed)
    }

    suspend fun updateStatus(jobId: Long, status: BatchJobStatus, error: String? = null) = withContext(Dispatchers.IO) {
        dao.updateStatus(jobId, status, error)
    }

    suspend fun getJobById(id: Long): BatchJobEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun cancelJob(id: Long) = withContext(Dispatchers.IO) {
        dao.cancelJob(id)
    }

    suspend fun deleteJob(job: BatchJobEntity) = withContext(Dispatchers.IO) {
        dao.delete(job)
    }

    suspend fun deleteOldCompletedJobs(olderThanMillis: Long = 7 * 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dao.deleteOldCompletedJobs(cutoff)
    }

    suspend fun getRunningJobCount(): Int = withContext(Dispatchers.IO) {
        dao.getRunningJobCount()
    }
}
