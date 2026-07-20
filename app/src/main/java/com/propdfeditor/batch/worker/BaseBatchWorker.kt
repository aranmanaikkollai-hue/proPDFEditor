package com.propdfeditor.batch.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

abstract class BaseBatchWorker(
    context: Context,
    params: WorkerParameters,
    protected val repository: BatchJobRepository,
    protected val notificationManager: BatchNotificationManager
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
    }

    protected var currentJob: BatchJobEntity? = null
    protected var isCancelled = false

    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId == -1L) {
            return Result.failure(workDataOf(KEY_ERROR to "Invalid job ID"))
        }

        currentJob = repository.getJobById(jobId) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Job not found")
        )

        setForeground(notificationManager.createForegroundInfo(currentJob!!))

        return try {
            repository.updateStatus(jobId, BatchJobStatus.RUNNING)
            
            val result = withContext(Dispatchers.IO) {
                executeBatch()
            }

            if (isCancelled) {
                repository.updateStatus(jobId, BatchJobStatus.CANCELLED)
                Result.failure(workDataOf(KEY_ERROR to "Cancelled by user"))
            } else {
                repository.updateStatus(jobId, BatchJobStatus.COMPLETED)
                Result.success(result)
            }
        } catch (e: Exception) {
            Timber.e(e, "Batch worker failed for job $jobId")
            repository.updateStatus(jobId, BatchJobStatus.FAILED, e.message)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    protected abstract suspend fun executeBatch(): androidx.work.Data

    protected suspend fun updateProgress(progress: Int, processed: Int, total: Int) {
        val jobId = currentJob?.id ?: return
        repository.updateProgress(jobId, progress, processed)
        setProgress(
            workDataOf(
                KEY_PROGRESS to progress,
                KEY_PROCESSED to processed,
                KEY_TOTAL to total
            )
        )
        notificationManager.updateProgress(jobId, progress, processed, total)
    }

    override suspend fun getForegroundInfo() = notificationManager.createForegroundInfo(currentJob!!)
}
