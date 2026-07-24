package com.propdf.editor.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfOperationsManager: PdfOperationsManager,
    private val pdfOperationsRepository: PdfOperationsRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "pdf_operation"
        const val KEY_OPERATION_TYPE = "operation_type"
        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OPTIONS = "options_json"
        const val KEY_PAGE_RANGE = "page_range"
        const val KEY_PASSWORD = "password"
    }

    override suspend fun doWork(): Result {
        return try {
            val operationType = inputData.getString(KEY_OPERATION_TYPE) ?: return Result.failure()
            val inputUris = inputData.getStringArray(KEY_INPUT_URIS) ?: emptyArray()
            val outputUri = inputData.getString(KEY_OUTPUT_URI)
            val optionsJson = inputData.getString(KEY_OPTIONS)
            val pageRange = inputData.getString(KEY_PAGE_RANGE)
            val password = inputData.getString(KEY_PASSWORD)

            // Parse options from JSON string if needed
            val options = optionsJson?.let { parseOptions(it) } ?: emptyMap()

            val result = when (operationType) {
                "merge" -> pdfOperationsManager.mergePdfs(inputUris.toList(), outputUri)
                "split" -> pdfOperationsManager.splitPdf(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    pageRange ?: return Result.failure(),
                    outputUri
                )
                "compress" -> pdfOperationsManager.compressPdf(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    options
                )
                "rotate" -> pdfOperationsManager.rotatePdf(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    options
                )
                "reorder" -> pdfOperationsManager.reorderPages(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    options
                )
                "add_password" -> pdfOperationsManager.addPassword(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    password ?: return Result.failure()
                )
                "remove_password" -> pdfOperationsManager.removePassword(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    password ?: return Result.failure()
                )
                "watermark" -> pdfOperationsManager.addWatermark(
                    inputUris.firstOrNull() ?: return Result.failure(),
                    outputUri,
                    options
                )
                else -> Result.failure()
            }

            result
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun parseOptions(jsonString: String): Map<String, String> {
        return try {
            val result = mutableMapOf<String, String>()
            // Simple JSON parsing without org.json dependency
            // Format: {"key1":"value1","key2":"value2"}
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val content = trimmed.substring(1, trimmed.length - 1)
                val pairs = content.split(",").map { it.trim() }
                for (pair in pairs) {
                    val colonIndex = pair.indexOf(":")
                    if (colonIndex > 0) {
                        val key = pair.substring(0, colonIndex).trim().trim('"')
                        val value = pair.substring(colonIndex + 1).trim().trim('"')
                        result[key] = value
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
