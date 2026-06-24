package com.propdf.editor.data.index

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.logger.AppLogger
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.data.local.entity.IndexingStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class DocumentIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ocrIndexingEngine: OcrIndexingEngine,
    private val searchIndexDao: SearchIndexDao,
    private val pdfDocumentDao: PdfDocumentDao,
    private val logger: AppLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val pendingDocs = searchIndexDao.getPendingForProcessing(limit = 5)
            if (pendingDocs.isEmpty()) return Result.success()

            var successCount = 0
            var failureCount = 0

            for (doc in pendingDocs) {
                try {
                    searchIndexDao.updateStatus(doc.documentId, IndexingStatus.PROCESSING)
                    val pdfDoc = pdfDocumentDao.getById(doc.documentId) ?: continue

                    val result = ocrIndexingEngine.indexDocument(
                        documentUri = android.net.Uri.parse(pdfDoc.uri),
                        documentId = doc.documentId,
                        pageCount = pdfDoc.pageCount
                    )

                    result.fold(
                        onSuccess = { ocrText ->
                            searchIndexDao.updateOcrResult(
                                documentId = doc.documentId,
                                ocrText = ocrText,
                                status = if (ocrText.isBlank()) IndexingStatus.PARTIAL else IndexingStatus.COMPLETED
                            )
                            successCount++
                        },
                        onFailure = { error ->
                            searchIndexDao.updateStatus(doc.documentId, IndexingStatus.FAILED)
                            logger.e(TAG, "OCR failed for ${doc.documentId}", error)
                            failureCount++
                        }
                    )
                } catch (e: Exception) {
                    searchIndexDao.updateStatus(doc.documentId, IndexingStatus.FAILED)
                    logger.e(TAG, "Unexpected error indexing ${doc.documentId}", e)
                    failureCount++
                }
            }

            if (failureCount > 0 && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success(workDataOf(
                    KEY_SUCCESS_COUNT to successCount,
                    KEY_FAILURE_COUNT to failureCount
                ))
            }
        } catch (e: Exception) {
            logger.e(TAG, "Worker failed", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "DocumentIndexWorker"
        private const val MAX_RETRIES = 3
        const val WORK_NAME = "ocr_indexing_work"
        const val KEY_SUCCESS_COUNT = "success_count"
        const val KEY_FAILURE_COUNT = "failure_count"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DocumentIndexWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
