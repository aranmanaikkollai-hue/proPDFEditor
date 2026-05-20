package com.propdf.viewer.pdf.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.propdf.viewer.pdf.CompressionOptions
import com.propdf.viewer.pdf.CompressionQuality
import com.propdf.viewer.pdf.PdfExportManager
import com.propdf.viewer.pdf.PdfOperationResult
import com.propdf.viewer.pdf.PdfOperationType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * WorkManager worker for background PDF operations.
 * Supports all operation types with progress reporting and cancellation.
 *
 * Input data:
 * - OPERATION_TYPE: String enum of PdfOperationType
 * - SOURCE_URIS: Array of source file URIs
 * - PAGE_INDICES: Array of page indices (for extract/reorder/rotate/delete)
 * - DEGREES: Int rotation degrees
 * - COMPRESSION_QUALITY: String enum (LOW/MEDIUM/HIGH)
 * - GRAYSCALE: Boolean
 * - OPTIMIZE_FONTS: Boolean
 * - OUTPUT_NAME: String output filename
 */
@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val exportManager: PdfExportManager
) : CoroutineWorker(context, params) {

    companion object {
        const val OPERATION_TYPE = "operation_type"
        const val SOURCE_URIS = "source_uris"
        const val PAGE_INDICES = "page_indices"
        const val DEGREES = "degrees"
        const val COMPRESSION_QUALITY = "compression_quality"
        const val GRAYSCALE = "grayscale"
        const val OPTIMIZE_FONTS = "optimize_fonts"
        const val OUTPUT_NAME = "output_name"

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_PERCENT = "progress_percent"
        const val RESULT_OPERATION_ID = "result_operation_id"
        const val RESULT_OUTPUT_PATH = "result_output_path"
        const val RESULT_ERROR = "result_error"
    }

    override suspend fun doWork(): Result {
        val operationType = try {
            PdfOperationType.valueOf(inputData.getString(OPERATION_TYPE) ?: "MERGE")
        } catch (e: Exception) {
            return Result.failure(
                Data.Builder().putString(RESULT_ERROR, "Invalid operation type").build()
            )
        }

        val sourceUris = inputData.getStringArray(SOURCE_URIS)?.map {
            android.net.Uri.parse(it)
        } ?: emptyList()

        if (sourceUris.isEmpty()) {
            return Result.failure(
                Data.Builder().putString(RESULT_ERROR, "No source files provided").build()
            )
        }

        return try {
            val operationId = when (operationType) {
                PdfOperationType.MERGE -> {
                    exportManager.merge(sourceUris)
                }
                PdfOperationType.SPLIT -> {
                    exportManager.split(sourceUris.first())
                }
                PdfOperationType.EXTRACT -> {
                    val indices = inputData.getIntArray(PAGE_INDICES)?.toList() ?: emptyList()
                    exportManager.extract(sourceUris.first(), indices)
                }
                PdfOperationType.REORDER -> {
                    val indices = inputData.getIntArray(PAGE_INDICES)?.toList() ?: emptyList()
                    exportManager.reorder(sourceUris.first(), indices)
                }
                PdfOperationType.DUPLICATE -> {
                    val pageIndex = inputData.getIntArray(PAGE_INDICES)?.firstOrNull() ?: 0
                    exportManager.duplicate(sourceUris.first(), pageIndex)
                }
                PdfOperationType.ROTATE -> {
                    val indices = inputData.getIntArray(PAGE_INDICES)?.toList() ?: emptyList()
                    val degrees = inputData.getInt(DEGREES, 90)
                    exportManager.rotate(sourceUris.first(), indices, degrees)
                }
                PdfOperationType.DELETE -> {
                    val indices = inputData.getIntArray(PAGE_INDICES)?.toList() ?: emptyList()
                    exportManager.delete(sourceUris.first(), indices)
                }
                PdfOperationType.COMPRESS -> {
                    val qualityStr = inputData.getString(COMPRESSION_QUALITY) ?: "MEDIUM"
                    val quality = try {
                        CompressionQuality.valueOf(qualityStr)
                    } catch (e: Exception) {
                        CompressionQuality.MEDIUM
                    }
                    val options = CompressionOptions(
                        quality = quality,
                        grayscale = inputData.getBoolean(GRAYSCALE, false),
                        optimizeFonts = inputData.getBoolean(OPTIMIZE_FONTS, true)
                    )
                    exportManager.compress(sourceUris.first(), options)
                }
            }

            // Poll for completion
            var attempts = 0
            while (attempts < 300) { // 5 minutes max (1s * 300)
                if (isStopped) {
                    exportManager.cancelOperation(operationId)
                    return Result.failure(
                        Data.Builder()
                            .putString(RESULT_ERROR, "Worker was stopped")
                            .build()
                    )
                }

                val result = exportManager.getOperationResult(operationId)
                when (result) {
                    is PdfOperationResult.Success -> {
                        return Result.success(
                            Data.Builder()
                                .putString(RESULT_OPERATION_ID, operationId)
                                .putString(RESULT_OUTPUT_PATH, result.outputFile.absolutePath)
                                .putInt(PROGRESS_CURRENT, result.pagesProcessed)
                                .putInt(PROGRESS_TOTAL, result.pagesProcessed)
                                .putFloat(PROGRESS_PERCENT, 100f)
                                .build()
                        )
                    }
                    is PdfOperationResult.Failure -> {
                        return Result.failure(
                            Data.Builder()
                                .putString(RESULT_ERROR, result.error.message)
                                .putString(RESULT_OPERATION_ID, operationId)
                                .build()
                        )
                    }
                    is PdfOperationResult.Cancelled -> {
                        return Result.failure(
                            Data.Builder()
                                .putString(RESULT_ERROR, "Operation cancelled")
                                .putString(RESULT_OPERATION_ID, operationId)
                                .build()
                        )
                    }
                    is PdfOperationResult.InProgress -> {
                        setProgress(
                            Data.Builder()
                                .putInt(PROGRESS_CURRENT, result.currentPage)
                                .putInt(PROGRESS_TOTAL, result.totalPages)
                                .putFloat(PROGRESS_PERCENT, result.percentComplete)
                                .build()
                        )
                    }
                    null -> {}
                }
                delay(1000)
                attempts++
            }

            Result.failure(
                Data.Builder().putString(RESULT_ERROR, "Operation timed out").build()
            )
        } catch (e: Exception) {
            Result.failure(
                Data.Builder().putString(RESULT_ERROR, e.message ?: "Unknown error").build()
            )
        }
    }
}
