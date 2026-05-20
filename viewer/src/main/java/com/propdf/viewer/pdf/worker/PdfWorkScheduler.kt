package com.propdf.viewer.pdf.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.propdf.viewer.pdf.CompressionOptions
import com.propdf.viewer.pdf.PdfOperationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Schedules PDF operations via WorkManager for background execution.
 * Provides observability via WorkInfo flow.
 */
class PdfWorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Enqueue a PDF operation as a background work request.
     */
    fun schedule(
        operationType: PdfOperationType,
        sourceUris: List<String>,
        pageIndices: IntArray? = null,
        degrees: Int = 90,
        compressionOptions: CompressionOptions? = null,
        outputName: String? = null
    ): androidx.work.Operation {
        val inputData = Data.Builder()
            .putString(PdfOperationWorker.OPERATION_TYPE, operationType.name)
            .putStringArray(PdfOperationWorker.SOURCE_URIS, sourceUris.toTypedArray())
            .apply {
                pageIndices?.let { putIntArray(PdfOperationWorker.PAGE_INDICES, it) }
                putInt(PdfOperationWorker.DEGREES, degrees)
                compressionOptions?.let { opts ->
                    putString(PdfOperationWorker.COMPRESSION_QUALITY, opts.quality.name)
                    putBoolean(PdfOperationWorker.GRAYSCALE, opts.grayscale)
                    putBoolean(PdfOperationWorker.OPTIMIZE_FONTS, opts.optimizeFonts)
                }
                outputName?.let { putString(PdfOperationWorker.OUTPUT_NAME, it) }
            }
            .build()

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PdfOperationWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag("pdf_operation")
            .addTag("${operationType.name.lowercase()}")
            .build()

        return workManager.enqueueUniqueWork(
            "pdf_${operationType.name}_${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Observe progress of a specific work request.
     */
    fun observeWork(workId: java.util.UUID): Flow<WorkInfo> {
        return workManager.getWorkInfoByIdLiveData(workId).asFlow()
    }

    /**
     * Observe all PDF operations.
     */
    fun observeAllPdfWork(): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosByTagLiveData("pdf_operation").asFlow()
    }

    /**
     * Cancel all pending PDF operations.
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag("pdf_operation")
    }

    /**
     * Cancel a specific operation by work ID.
     */
    fun cancel(workId: java.util.UUID) {
        workManager.cancelWorkById(workId)
    }

    private fun <T> androidx.lifecycle.LiveData<T>.asFlow(): kotlinx.coroutines.flow.Flow<T> {
        return kotlinx.coroutines.flow.callbackFlow {
            val observer = androidx.lifecycle.Observer<T> { trySend(it) }
            observeForever(observer)
            awaitClose { removeObserver(observer) }
        }
    }
}
