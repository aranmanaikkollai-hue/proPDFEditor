package com.propdf.editor.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.model.CropConfig
import com.propdf.core.domain.model.ResizeConfig
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.HeaderFooterConfig
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.core.domain.model.BackgroundConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.utils.FileHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based background PDF operations.
 * 
 * Benefits over raw coroutines:
 * - Survives process death
 * - Respects battery optimizations (Doze, App Standby)
 * - Chained operations with automatic retry
 * - Observable progress via WorkInfo
 * - Constrained to unmetered network/charging when appropriate
 */
@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfOps: PdfOperationsManager,
    private val pdfOperationsRepository: PdfOperationsRepository
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val KEY_OPERATION = "operation"
        const val KEY_INPUT_FILE = "input_file"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_EXTRA_PARAM = "extra_param"
        const val KEY_EXTRA_PARAM2 = "extra_param2"

        // Operation types
        const val OP_MERGE = "merge"
        const val OP_SPLIT = "split"
        const val OP_COMPRESS = "compress"
        const val OP_ENCRYPT = "encrypt"
        const val OP_DECRYPT = "decrypt"
        const val OP_WATERMARK = "watermark"
        const val OP_ROTATE = "rotate"
        const val OP_DELETE_PAGES = "delete_pages"
        const val OP_PAGE_NUMBERS = "page_numbers"
        const val OP_HEADER_FOOTER = "header_footer"
        const val OP_IMAGES_TO_PDF = "images_to_pdf"
        const val OP_PDF_TO_IMAGES = "pdf_to_images"

        // ── Page-level operation protocol (Uri-based, routes through PdfOperationsRepository) ──
        const val KEY_OPERATION_TYPE = "operation_type"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_PAGE_NUMBERS = "page_numbers"

        const val OP_DUPLICATE_PAGES = "duplicate_pages"
        const val OP_MOVE_PAGES = "move_pages"
        const val OP_EXTRACT_PAGES = "extract_pages"
        const val OP_ROTATE_PAGES = "rotate_pages"
        const val OP_CROP_PAGES = "crop_pages"
        const val OP_RESIZE_PAGES = "resize_pages"
        const val OP_MIRROR_PAGES = "mirror_pages"
        const val OP_SPLIT_SIZE = "split_size"
        const val OP_SPLIT_BOOKMARK = "split_bookmark"
        const val OP_SPLIT_N = "split_n"
        const val OP_ADD_PAGE_NUMBERS = "add_page_numbers"
        const val OP_ADD_HEADER_FOOTER = "add_header_footer"
        const val OP_ADD_WATERMARK = "add_watermark"
        const val OP_ADD_BACKGROUND = "add_background"
        const val OP_OPTIMIZE = "optimize"

        /**
         * Enqueue a single PDF operation with constraints.
         */
        fun enqueue(
            context: Context,
            operation: String,
            inputFile: File,
            outputName: String,
            extraParam: String? = null,
            extraParam2: String? = null,
            requiresCharging: Boolean = false
        ): UUID {
            val data = workDataOf(
                KEY_OPERATION to operation,
                KEY_INPUT_FILE to inputFile.absolutePath,
                KEY_OUTPUT_NAME to outputName,
                KEY_EXTRA_PARAM to extraParam,
                KEY_EXTRA_PARAM2 to extraParam2
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(requiresCharging)
                .build()

            val request = OneTimeWorkRequestBuilder<PdfOperationWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("pdf_operation")
                .addTag(operation)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        /**
         * Chain multiple operations into a pipeline.
         */
        fun enqueuePipeline(
            context: Context,
            operations: List<OperationSpec>
        ): UUID? {
            if (operations.isEmpty()) return null

            var continuation: WorkContinuation? = null

            for ((index, spec) in operations.withIndex()) {
                val data = workDataOf(
                    KEY_OPERATION to spec.operation,
                    KEY_INPUT_FILE to spec.inputFile.absolutePath,
                    KEY_OUTPUT_NAME to "${spec.outputName}_step$index",
                    KEY_EXTRA_PARAM to spec.extraParam,
                    KEY_EXTRA_PARAM2 to spec.extraParam2
                )

                val request = OneTimeWorkRequestBuilder<PdfOperationWorker>()
                    .setInputData(data)
                    .addTag("pdf_pipeline")
                    .build()

                continuation = if (continuation == null) {
                    WorkManager.getInstance(context).beginWith(request)
                } else {
                    continuation.then(request)
                }
            }

            val finalRequest = continuation?.enqueue()
            return operations.lastOrNull()?.let { 
                // Return ID of last operation for observation
                continuation?.getWorkInfos()?.get()?.lastOrNull()?.id 
            }
        }

        data class OperationSpec(
            val operation: String,
            val inputFile: File,
            val outputName: String,
            val extraParam: String? = null,
            val extraParam2: String? = null
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationType = inputData.getString(KEY_OPERATION_TYPE)
        if (operationType != null) {
            return@withContext doPageLevelWork(operationType)
        }

        val operation = inputData.getString(KEY_OPERATION) ?: return@withContext Result.failure()
        val inputPath = inputData.getString(KEY_INPUT_FILE) ?: return@withContext Result.failure()
        val outputName = inputData.getString(KEY_OUTPUT_NAME) ?: "output"
        val extraParam = inputData.getString(KEY_EXTRA_PARAM)
        val extraParam2 = inputData.getString(KEY_EXTRA_PARAM2)

        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            return@withContext Result.failure(
                workDataOf("error" to "Input file not found")
            )
        }

        val outputFile = FileHelper.tempFile(applicationContext, outputName)

        try {
            val result = when (operation) {
                OP_MERGE -> {
                    // For merge, extraParam contains comma-separated file paths
                    val files = extraParam?.split(",")?.map { File(it) } ?: listOf(inputFile)
                    pdfOps.mergePdfs(files, outputFile)
                }
                OP_SPLIT -> {
                    val rangeStr = extraParam ?: "1-1"
                    val parts = rangeStr.split("-")
                    val from = parts.getOrNull(0)?.toIntOrNull() ?: 1
                    val to = parts.getOrNull(1)?.toIntOrNull() ?: from
                    pdfOps.splitPdf(inputFile, outputFile.parentFile ?: applicationContext.cacheDir, 
                        listOf(from..to)).mapCatching { files ->
                        files.firstOrNull() ?: throw IllegalStateException("Split produced no output files")
                    }
                }
                OP_COMPRESS -> {
                    val level = extraParam?.toIntOrNull() ?: 6
                    pdfOps.compressPdf(inputFile, outputFile, level)
                }
                OP_ENCRYPT -> {
                    val password = extraParam ?: "password"
                    pdfOps.encryptPdf(inputFile, outputFile, password, password)
                }
                OP_DECRYPT -> {
                    val password = extraParam ?: ""
                    pdfOps.removePdfPassword(inputFile, outputFile, password)
                }
                OP_WATERMARK -> {
                    val text = extraParam ?: "DRAFT"
                    val opacity = extraParam2?.toFloatOrNull() ?: 0.3f
                    pdfOps.addTextWatermark(inputFile, outputFile, text, opacity)
                }
                OP_ROTATE -> {
                    val degrees = extraParam?.toIntOrNull() ?: 90
                    val pageCount = extraParam2?.toIntOrNull() ?: 1
                    pdfOps.rotatePages(inputFile, outputFile, 
                        (1..pageCount).associateWith { degrees })
                }
                OP_DELETE_PAGES -> {
                    val pages = extraParam?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                    pdfOps.deletePages(inputFile, outputFile, pages)
                }
                OP_PAGE_NUMBERS -> {
                    val format = extraParam ?: "Page %d of %d"
                    pdfOps.addPageNumbers(inputFile, outputFile, format)
                }
                OP_HEADER_FOOTER -> {
                    val header = extraParam
                    val footer = extraParam2
                    pdfOps.addHeaderFooter(inputFile, outputFile, header, footer)
                }
                OP_IMAGES_TO_PDF -> {
                    val imagePaths = extraParam?.split(",")?.map { File(it) } ?: emptyList()
                    pdfOps.imagesToPdf(imagePaths, outputFile)
                }
                else -> kotlin.Result.failure(IllegalArgumentException("Unknown operation: $operation"))
            }

            result.fold(
                onSuccess = { file ->
                    // Save to downloads
                    FileHelper.saveToDownloads(applicationContext, file)
                    Result.success(workDataOf(
                        "output_path" to file.absolutePath,
                        "output_name" to outputName
                    ))
                },
                onFailure = { error ->
                    Result.failure(workDataOf("error" to (error.message ?: "Unknown error")))
                }
            )
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }

    /**
     * Handles the Uri-based, page-level operation protocol used by PageEditorViewModel
     * and ToolsActivity's batch pipeline. Dispatches to PdfOperationsRepository, which
     * already implements all of these operations.
     */
    private suspend fun doPageLevelWork(operationType: String): Result {
        val sourceUriStr = inputData.getString(KEY_SOURCE_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing source URI"))
        val sourceUri = Uri.parse(sourceUriStr)
        val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: emptyList()
        val configJson = inputData.getString(KEY_CONFIG_JSON)

        val appResult: AppResult<*> = try {
            when (operationType) {
                OP_DELETE_PAGES -> pdfOperationsRepository.deletePages(sourceUri, pages)
                OP_DUPLICATE_PAGES -> pdfOperationsRepository.duplicatePages(sourceUri, pages)
                OP_MOVE_PAGES -> pdfOperationsRepository.movePages(
                    sourceUri, pages, inputData.getInt("target_position", 0)
                )
                OP_EXTRACT_PAGES -> pdfOperationsRepository.extractPages(
                    sourceUri, pages, inputData.getString("output_name") ?: "extracted"
                )
                OP_ROTATE_PAGES -> pdfOperationsRepository.rotatePages(
                    sourceUri, pages, inputData.getInt("degrees", 90)
                )
                OP_CROP_PAGES -> pdfOperationsRepository.cropPages(
                    sourceUri, pages, json.decodeFromString<CropConfig>(configJson!!)
                )
                OP_RESIZE_PAGES -> pdfOperationsRepository.resizePages(
                    sourceUri, pages, json.decodeFromString<ResizeConfig>(configJson!!)
                )
                OP_MIRROR_PAGES -> pdfOperationsRepository.mirrorPages(
                    sourceUri, pages, inputData.getBoolean("horizontal", true)
                )
                OP_SPLIT_SIZE -> pdfOperationsRepository.splitBySize(
                    sourceUri, inputData.getInt("max_size_mb", 10),
                    inputData.getString("output_prefix") ?: "split"
                )
                OP_SPLIT_BOOKMARK -> pdfOperationsRepository.splitByBookmark(
                    sourceUri, inputData.getString("output_prefix") ?: "split"
                )
                OP_SPLIT_N -> pdfOperationsRepository.splitEveryNPages(
                    sourceUri, inputData.getInt("n_pages", 1),
                    inputData.getString("output_prefix") ?: "split"
                )
                OP_ADD_PAGE_NUMBERS -> pdfOperationsRepository.addPageNumbers(
                    sourceUri, json.decodeFromString<PageNumberConfig>(configJson!!)
                )
                OP_ADD_HEADER_FOOTER -> pdfOperationsRepository.addHeaderFooter(
                    sourceUri, json.decodeFromString<HeaderFooterConfig>(configJson!!)
                )
                OP_ADD_WATERMARK -> pdfOperationsRepository.addWatermark(
                    sourceUri, json.decodeFromString<WatermarkConfig>(configJson!!)
                )
                OP_ADD_BACKGROUND -> pdfOperationsRepository.addBackground(
                    sourceUri, json.decodeFromString<BackgroundConfig>(configJson!!)
                )
                OP_COMPRESS -> pdfOperationsRepository.compressPdf(
                    sourceUri, json.decodeFromString(configJson!!)
                )
                OP_OPTIMIZE -> pdfOperationsRepository.optimizePdf(
                    sourceUri, inputData.getBoolean("aggressive", false)
                )
                else -> AppResult.Error("Unknown page-level operation: $operationType")
            }
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Operation failed", e)
        }

        return when (appResult) {
            is AppResult.Success -> {
                // Result may be a single Uri or a List<Uri> (splits); store the first
                // as the primary result either way for a consistent output contract.
                val resultUri = when (val data = appResult.data) {
                    is Uri -> data.toString()
                    is List<*> -> (data.firstOrNull() as? Uri)?.toString()
                    else -> null
                }
                Result.success(workDataOf(KEY_RESULT_URI to resultUri))
            }
            is AppResult.Error -> Result.failure(workDataOf(KEY_ERROR_MESSAGE to appResult.message))
            is AppResult.Loading -> Result.retry()
        }
    }
}
