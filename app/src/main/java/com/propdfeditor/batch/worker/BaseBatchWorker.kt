package com.propdfeditor.batch.worker

import com.propdfeditor.core.util.toSafeUserMessage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.BatchOutcome
import com.propdfeditor.batch.util.BatchOutcomeResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /** Per-file results, reported by the worker; used to decide the final job status. */
    protected var filesSucceeded = 0
        private set
    protected var filesFailed = 0
        private set

    protected fun recordSuccess() {
        filesSucceeded++
    }

    protected fun recordFailure() {
        filesFailed++
    }

    /** For workers that already keep their own counters (delete, rename). */
    protected fun reportCounts(succeeded: Int, failed: Int) {
        filesSucceeded = succeeded
        filesFailed = failed
    }

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

            when (BatchOutcomeResolver.resolve(filesSucceeded, filesFailed, isCancelled)) {
                BatchOutcome.CANCELLED -> {
                    repository.updateStatus(jobId, BatchJobStatus.CANCELLED)
                    Result.failure(workDataOf(KEY_ERROR to "Cancelled by user"))
                }
                BatchOutcome.FAILED -> {
                    // Every attempted file failed: never report this as COMPLETED.
                    val message = "None of the $filesFailed file(s) could be processed."
                    repository.updateStatus(jobId, BatchJobStatus.FAILED, message)
                    Result.failure(workDataOf(KEY_ERROR to message))
                }
                BatchOutcome.COMPLETED, BatchOutcome.COMPLETED_WITH_ERRORS -> {
                    // Partial success keeps the project's existing behaviour: COMPLETED.
                    repository.updateStatus(jobId, BatchJobStatus.COMPLETED)
                    Result.success(result)
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled us: record it, then let cancellation propagate.
            withContext(NonCancellable) {
                repository.updateStatus(jobId, BatchJobStatus.CANCELLED)
            }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Batch worker failed for job $jobId")
            repository.updateStatus(jobId, BatchJobStatus.FAILED, e.toSafeUserMessage("This batch job could not be completed."))
            Result.failure(workDataOf(KEY_ERROR to e.toSafeUserMessage("This batch job could not be completed.")))
        }
    }

    protected abstract suspend fun executeBatch(): androidx.work.Data

    /** Removes an output file that was created for an operation that then failed. */
    protected fun deleteOutputQuietly(uri: Uri?) {
        if (uri == null) return
        try {
            DocumentFile.fromSingleUri(applicationContext, uri)?.delete()
        } catch (e: Exception) {
            Timber.w(e, "Could not delete partial output $uri")
        }
    }

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
