package com.propdf.annotations.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.persistence.AnnotationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager tasks for background annotation processing.
 * Handles auto-save, export, flatten, and burn operations.
 */
@HiltWorker
class AnnotationAutoSaveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: AnnotationRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Triggered by periodic work - actual save handled by ViewModel
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "annotation_auto_save"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AnnotationAutoSaveWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

@HiltWorker
class AnnotationExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val exporter: PdfAnnotationExporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val inputPath = inputData.getString(KEY_INPUT_PATH) ?: return Result.failure()
        val outputPath = inputData.getString(KEY_OUTPUT_PATH) ?: return Result.failure()
        val operation = inputData.getString(KEY_OPERATION) ?: "flatten"

        setProgress(workDataOf(KEY_PROGRESS to 0))

        return try {
            val success = when (operation) {
                "flatten" -> {
                    setProgress(workDataOf(KEY_PROGRESS to 25))
                    val result = exporter.flattenAnnotations(
                        File(inputPath),
                        File(outputPath),
                        documentId
                    )
                    setProgress(workDataOf(KEY_PROGRESS to 100))
                    result
                }
                "burn" -> {
                    setProgress(workDataOf(KEY_PROGRESS to 10))
                    val result = exporter.burnAnnotationsIntoPdf(
                        File(inputPath),
                        File(outputPath),
                        documentId
                    )
                    setProgress(workDataOf(KEY_PROGRESS to 100))
                    result
                }
                "export_json" -> {
                    setProgress(workDataOf(KEY_PROGRESS to 50))
                    val result = exporter.exportAnnotationsToJson(
                        documentId,
                        File(outputPath)
                    )
                    setProgress(workDataOf(KEY_PROGRESS to 100))
                    result
                }
                else -> false
            }

            if (success) {
                Result.success(
                    workDataOf(
                        KEY_OUTPUT_PATH to outputPath,
                        KEY_OPERATION to operation
                    )
                )
            } else {
                Result.failure(
                    workDataOf(KEY_ERROR to "Operation failed")
                )
            }
        } catch (e: Exception) {
            Result.failure(
                workDataOf(KEY_ERROR to (e.message ?: "Unknown error"))
            )
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_OPERATION = "operation"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        fun createFlattenWorkRequest(
            documentId: String,
            inputFile: File,
            outputFile: File
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_DOCUMENT_ID to documentId,
                KEY_INPUT_PATH to inputFile.absolutePath,
                KEY_OUTPUT_PATH to outputFile.absolutePath,
                KEY_OPERATION to "flatten"
            )

            val constraints = Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<AnnotationExportWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .build()
        }

        fun createBurnWorkRequest(
            documentId: String,
            inputFile: File,
            outputFile: File
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_DOCUMENT_ID to documentId,
                KEY_INPUT_PATH to inputFile.absolutePath,
                KEY_OUTPUT_PATH to outputFile.absolutePath,
                KEY_OPERATION to "burn"
            )

            return OneTimeWorkRequestBuilder<AnnotationExportWorker>()
                .setInputData(inputData)
                .build()
        }

        fun createExportJsonWorkRequest(
            documentId: String,
            outputFile: File
        ): OneTimeWorkRequest {
            val inputData = workDataOf(
                KEY_DOCUMENT_ID to documentId,
                KEY_OUTPUT_PATH to outputFile.absolutePath,
                KEY_OPERATION to "export_json"
            )

            return OneTimeWorkRequestBuilder<AnnotationExportWorker>()
                .setInputData(inputData)
                .build()
        }
    }
}
