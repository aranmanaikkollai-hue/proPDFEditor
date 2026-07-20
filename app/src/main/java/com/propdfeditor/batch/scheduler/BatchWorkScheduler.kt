package com.propdfeditor.batch.scheduler

import android.content.Context
import androidx.work.*
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.worker.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BatchJobRepository
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleBatchJob(job: BatchJobEntity): UUID = withContext(Dispatchers.IO) {
        val workerClass = when (job.type) {
            BatchJobType.MERGE -> MergeWorker::class.java
            BatchJobType.SPLIT -> SplitWorker::class.java
            BatchJobType.RENAME -> RenameWorker::class.java
            BatchJobType.WATERMARK -> WatermarkWorker::class.java
            BatchJobType.ROTATE -> RotateWorker::class.java
            BatchJobType.COMPRESS -> CompressWorker::class.java
            BatchJobType.OCR -> OcrWorker::class.java
            BatchJobType.ENCRYPT -> EncryptWorker::class.java
            BatchJobType.DECRYPT -> DecryptWorker::class.java
            BatchJobType.EXPORT -> ExportWorker::class.java
            BatchJobType.DELETE -> DeleteWorker::class.java
        }

        val inputData = workDataOf(
            BaseBatchWorker.KEY_JOB_ID to job.id
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MergeWorker>()
            .setWorkerFactory(BatchWorkerFactory::class.java)
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("batch_job_${job.id}")
            .addTag("batch_type_${job.type.name}")
            .build()

        // Actually use the correct worker class
        val actualRequest = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("batch_job_${job.id}")
            .addTag("batch_type_${job.type.name}")
            .build()

        workManager.enqueueUniqueWork(
            "batch_job_${job.id}",
            ExistingWorkPolicy.KEEP,
            actualRequest
        )

        // Update job with work request ID
        val updatedJob = job.copy(workRequestId = actualRequest.id.toString())
        repository.updateJob(updatedJob)

        actualRequest.id
    }

    fun cancelJob(jobId: Long) {
        workManager.cancelUniqueWork("batch_job_$jobId")
    }

    fun observeJob(workId: UUID) = workManager.getWorkInfoByIdLiveData(workId)

    fun observeAllBatchJobs() = workManager.getWorkInfosByTagLiveData("batch_type")

    fun cleanupOldWork() {
        workManager.pruneWork()
    }
}
