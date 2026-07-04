package com.propdf.editor.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import java.io.File

@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pdfOperationsRepository: PdfOperationsRepository,
    private val json: Json
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_OPERATION_TYPE = "operation_type"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_PAGE_NUMBERS = "page_numbers"
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val OP_DELETE_PAGES = "delete_pages"
        const val OP_ROTATE_PAGES = "rotate_pages"
        const val OP_ADD_PAGE_NUMBERS = "add_page_numbers"
        const val OP_ADD_HEADER_FOOTER = "add_header_footer"
        const val OP_ADD_WATERMARK = "add_watermark"
        const val OP_COMPRESS = "compress"
    }

    override suspend fun doWork(): Result {
        val operationType = inputData.getString(KEY_OPERATION_TYPE) ?: return Result.failure()
        val sourceUriString = inputData.getString(KEY_SOURCE_URI) ?: return Result.failure()
        val sourceUri = android.net.Uri.parse(sourceUriString)
        
        // Convert Uri to File for the repository interface
        val inputFile = sourceUri.path?.let { File(it) } ?: return Result.failure()
        
        // Create a temporary output file in the cache directory
        val outputFile = File(applicationContext.cacheDir, "pdf_op_${System.currentTimeMillis()}.pdf")

        setProgressAsync(Data.Builder().putString("status", "running").build())

        val result = when (operationType) {
            OP_DELETE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                pdfOperationsRepository.deletePages(inputFile, outputFile, pages)
            }
            OP_ROTATE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val degrees = inputData.getInt("degrees", 90)
                // The interface expects a Map of page number to degrees
                pdfOperationsRepository.rotatePage(inputFile, outputFile, pages.associateWith { degrees.toFloat() })
            }
            OP_ADD_PAGE_NUMBERS -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = json.decodeFromString<PageNumberConfig>(configJson)
                pdfOperationsRepository.addPageNumbers(inputFile, outputFile, config)
            }
            OP_ADD_HEADER_FOOTER -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = json.decodeFromString<HeaderFooterConfig>(configJson)
                pdfOperationsRepository.addHeaderFooter(inputFile, outputFile, config)
            }
            OP_ADD_WATERMARK -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = json.decodeFromString<WatermarkConfig>(configJson)
                pdfOperationsRepository.addWatermark(inputFile, outputFile, config)
            }
            OP_COMPRESS -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = json.decodeFromString<CompressConfig>(configJson)
                pdfOperationsRepository.compress(inputFile, outputFile, config)
            }
            // For all other operations that are not yet implemented in the repository interface
            else -> {
                return Result.failure(
                    Data.Builder().putString(KEY_ERROR_MESSAGE, "Operation $operationType not implemented").build()
                )
            }
        }

        return when (result) {
            is AppResult.Success -> {
                val outputData = Data.Builder()
                    // Return the path of the newly created file
                    .putString(KEY_RESULT_URI, outputFile.absolutePath)
                    .putString("status", "completed")
                    .build()
                Result.success(outputData)
            }
            is AppResult.Error -> {
                val outputData = Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, result.exception.message ?: "Unknown error")
                    .putString("status", "failed")
                    .build()
                Result.failure(outputData)
            }
            else -> Result.failure()
        }
    }
}
