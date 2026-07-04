package com.propdf.editor.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfOperationsRepository: PdfOperationsRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_OPERATION_TYPE = "operation_type"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_PAGE_NUMBERS = "page_numbers"
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_RESULT_URIS = "result_uris"
        
        // Operation types
        const val OP_DELETE_PAGES = "delete_pages"
        const val OP_DUPLICATE_PAGES = "duplicate_pages"
        const val OP_MOVE_PAGES = "move_pages"
        const val OP_EXTRACT_PAGES = "extract_pages"
        const val OP_ROTATE_PAGES = "rotate_pages"
        const val OP_CROP_PAGES = "crop_pages"
        const val OP_RESIZE_PAGES = "resize_pages"
        const val OP_MIRROR_PAGES = "mirror_pages"
        const val OP_INSERT_BLANK = "insert_blank"
        const val OP_INSERT_IMAGE = "insert_image"
        const val OP_INSERT_PDF = "insert_pdf"
        const val OP_SPLIT_SIZE = "split_size"
        const val OP_SPLIT_BOOKMARK = "split_bookmark"
        const val OP_SPLIT_N = "split_n"
        const val OP_MERGE = "merge"
        const val OP_COMBINE_IMAGES = "combine_images"
        const val OP_ADD_PAGE_NUMBERS = "add_page_numbers"
        const val OP_ADD_HEADER_FOOTER = "add_header_footer"
        const val OP_ADD_WATERMARK = "add_watermark"
        const val OP_ADD_BACKGROUND = "add_background"
        const val OP_COMPRESS = "compress"
        const val OP_OPTIMIZE = "optimize"
    }

    override suspend fun doWork(): Result {
        val operationType = inputData.getString(KEY_OPERATION_TYPE) ?: return Result.failure()
        val sourceUriString = inputData.getString(KEY_SOURCE_URI) ?: return Result.failure()
        val sourceUri = android.net.Uri.parse(sourceUriString)
        
        setProgressAsync(Data.Builder().putString("status", "running").build())
        
        val result = when (operationType) {
            OP_DELETE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                pdfOperationsRepository.deletePages(sourceUri, pages)
            }
            OP_DUPLICATE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                pdfOperationsRepository.duplicatePages(sourceUri, pages)
            }
            OP_MOVE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val target = inputData.getInt("target_position", 0)
                pdfOperationsRepository.movePages(sourceUri, pages, target)
            }
            OP_EXTRACT_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val name = inputData.getString("output_name") ?: "extracted"
                pdfOperationsRepository.extractPages(sourceUri, pages, name)
            }
            OP_ROTATE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val degrees = inputData.getInt("degrees", 90)
                pdfOperationsRepository.rotatePages(sourceUri, pages, degrees)
            }
            OP_CROP_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<CropConfig>(configJson)
                pdfOperationsRepository.cropPages(sourceUri, pages, config)
            }
            OP_RESIZE_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<ResizeConfig>(configJson)
                pdfOperationsRepository.resizePages(sourceUri, pages, config)
            }
            OP_MIRROR_PAGES -> {
                val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList() ?: return Result.failure()
                val horizontal = inputData.getBoolean("horizontal", true)
                pdfOperationsRepository.mirrorPages(sourceUri, pages, horizontal)
            }
            OP_INSERT_BLANK -> {
                val position = inputData.getInt("position", 1)
                val width = inputData.getFloat("width", 595f)
                val height = inputData.getFloat("height", 842f)
                pdfOperationsRepository.insertBlankPage(sourceUri, position, width, height)
            }
            OP_SPLIT_SIZE -> {
                val maxSize = inputData.getInt("max_size_mb", 10)
                val prefix = inputData.getString("output_prefix") ?: "split"
                pdfOperationsRepository.splitBySize(sourceUri, maxSize, prefix)
            }
            OP_SPLIT_BOOKMARK -> {
                val prefix = inputData.getString("output_prefix") ?: "split"
                pdfOperationsRepository.splitByBookmark(sourceUri, prefix)
            }
            OP_SPLIT_N -> {
                val n = inputData.getInt("n_pages", 1)
                val prefix = inputData.getString("output_prefix") ?: "split"
                pdfOperationsRepository.splitEveryNPages(sourceUri, n, prefix)
            }
            OP_ADD_PAGE_NUMBERS -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<PageNumberConfig>(configJson)
                pdfOperationsRepository.addPageNumbers(sourceUri, config)
            }
            OP_ADD_HEADER_FOOTER -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<HeaderFooterConfig>(configJson)
                pdfOperationsRepository.addHeaderFooter(sourceUri, config)
            }
            OP_ADD_WATERMARK -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<WatermarkConfig>(configJson)
                pdfOperationsRepository.addWatermark(sourceUri, config)
            }
            OP_ADD_BACKGROUND -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<BackgroundConfig>(configJson)
                pdfOperationsRepository.addBackground(sourceUri, config)
            }
            OP_COMPRESS -> {
                val configJson = inputData.getString(KEY_CONFIG_JSON) ?: return Result.failure()
                val config = Json.decodeFromString<CompressConfig>(configJson)
                pdfOperationsRepository.compressPdf(sourceUri, config)
            }
            OP_OPTIMIZE -> {
                val aggressive = inputData.getBoolean("aggressive", false)
                pdfOperationsRepository.optimizePdf(sourceUri, aggressive)
            }
            else -> return Result.failure()
        }
        
        return when (result) {
            is AppResult.Success -> {
                val outputData = Data.Builder()
                    .putString(KEY_RESULT_URI, result.data.toString())
                    .putString("status", "completed")
                    .build()
                Result.success(outputData)
            }
            is AppResult.Error -> {
                val outputData = Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, result.exception.message)
                    .putString("status", "failed")
                    .build()
                Result.failure(outputData)
            }
            else -> Result.failure()
        }
    }
}
