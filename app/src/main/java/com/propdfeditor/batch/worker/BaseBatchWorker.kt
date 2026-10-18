package com.propdfeditor.batch.worker

import com.propdfeditor.core.util.toSafeUserMessage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.security.BatchSecretUnavailableException
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
                    clearSecretsQuietly(jobId)
                    Result.failure(workDataOf(KEY_ERROR to "Cancelled by user"))
                }
                BatchOutcome.FAILED -> {
                    // Every attempted file failed: never report this as COMPLETED.
                    val message = "None of the $filesFailed file(s) could be processed."
                    repository.updateStatus(jobId, BatchJobStatus.FAILED, message)
                    clearSecretsQuietly(jobId)
                    Result.failure(workDataOf(KEY_ERROR to message))
                }
                BatchOutcome.COMPLETED, BatchOutcome.COMPLETED_WITH_ERRORS -> {
                    // Partial success keeps the project's existing behaviour: COMPLETED.
                    repository.updateStatus(jobId, BatchJobStatus.COMPLETED)
                    clearSecretsQuietly(jobId)
                    Result.success(result)
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled us: record it, then let cancellation propagate.
            withContext(NonCancellable) {
                repository.updateStatus(jobId, BatchJobStatus.CANCELLED)
                clearSecretsQuietly(jobId)
            }
            throw e
        } catch (e: BatchSecretUnavailableException) {
            // A protected password could not be recovered (key missing/invalidated, corrupted data,
            // secrets already cleared). Fail with a fixed message; the user re-creates the job.
            // Only the reason is logged: never the exception object's content or any config.
            Timber.w("Batch job %d cannot run: protected secret unavailable (%s)", jobId, e.reason)
            repository.updateStatus(jobId, BatchJobStatus.FAILED, BatchSecretUnavailableException.USER_MESSAGE)
            clearSecretsQuietly(jobId)
            Result.failure(workDataOf(KEY_ERROR to BatchSecretUnavailableException.USER_MESSAGE))
        } catch (e: Exception) {
            Timber.e(e, "Batch worker failed for job $jobId")
            repository.updateStatus(jobId, BatchJobStatus.FAILED, e.toSafeUserMessage("This batch job could not be completed."))
            clearSecretsQuietly(jobId)
            Result.failure(workDataOf(KEY_ERROR to e.toSafeUserMessage("This batch job could not be completed.")))
        }
    }

    /**
     * The job configuration with protected secrets restored, held in memory only.
     * Never store, log or pass the returned string through WorkManager data.
     * Throws [BatchSecretUnavailableException] if a required secret cannot be recovered.
     */
    protected suspend fun resolvedConfigJson(requiredSecrets: Set<String> = emptySet()): String {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        return repository.resolveJobConfig(job, requiredSecrets)
    }

    /** Removes stored secrets after a terminal state; a failure here must not change the job outcome. */
    private suspend fun clearSecretsQuietly(jobId: Long) {
        try {
            repository.clearSecrets(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not clear stored secrets for job $jobId")
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
