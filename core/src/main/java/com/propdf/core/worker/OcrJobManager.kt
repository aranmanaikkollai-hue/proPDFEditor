package com.propdf.core.worker

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.data.local.OcrJobEntity
import com.propdf.core.domain.model.OcrBatchRequest
import com.propdf.core.domain.model.OcrJobStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrJobManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrJobDao: OcrJobDao
) {
    private val workManager = WorkManager.getInstance(context)
    private val gson = Gson()

    suspend fun enqueueBatchOcr(request: OcrBatchRequest): String {
        val jobId = UUID.randomUUID().toString()
        val entity = OcrJobEntity(
            id = jobId, imageUrisJson = gson.toJson(request.imageUris),
            languageCodesJson = gson.toJson(request.config.languages.map { it.code }),
            outputFormat = request.outputFormat.name, status = OcrJobStatus.PENDING,
            progress = 0, totalPages = request.imageUris.size, completedPages = 0,
            resultUri = null, errorMessage = null, createdAt = System.currentTimeMillis(), completedAt = null
        )
        ocrJobDao.insert(entity)
        val inputData = workDataOf(
            OcrWorker.KEY_JOB_ID to jobId, OcrWorker.KEY_IMAGE_URIS to gson.toJson(request.imageUris),
            OcrWorker.KEY_LANGUAGE_CODES to gson.toJson(request.config.languages.map { it.code }),
            OcrWorker.KEY_OUTPUT_FORMAT to request.outputFormat.name
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<OcrWorker>()
            .setInputData(inputData).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("ocr_batch").addTag("ocr_$jobId").build()
        workManager.enqueueUniqueWork("ocr_$jobId", ExistingWorkPolicy.KEEP, workRequest)
        return jobId
    }

    fun cancelJob(jobId: String) { workManager.cancelUniqueWork("ocr_$jobId") }
    fun cancelAllJobs() { workManager.cancelAllWorkByTag("ocr_batch") }
    fun observeJob(jobId: String): Flow<OcrJobStatus?> = ocrJobDao.getAll().map { jobs -> jobs.find { it.id == jobId }?.status }
    fun getAllJobs(): Flow<List<OcrJobEntity>> = ocrJobDao.getAll()
    fun getActiveJobs(): Flow<List<OcrJobEntity>> = ocrJobDao.getActiveJobs()
    suspend fun cleanupOldJobs(olderThanDays: Int = 7) {
        ocrJobDao.deleteOldCompleted(System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L))
    }
}
