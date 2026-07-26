package com.propdf.editor.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.UUID

@HiltWorker
class PdfOperationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfOperationsManager: PdfOperationsManager,
    private val pdfOperationsRepository: PdfOperationsRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "pdf_operation"

        // ── Generic WorkManager Data keys ──────────────────────────
        const val KEY_OPERATION_TYPE = "operation_type"
        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OPTIONS = "options_json"
        const val KEY_PAGE_RANGE = "page_range"
        const val KEY_PASSWORD = "password"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PAGE_NUMBERS = "page_numbers"
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_EXTRA_PARAM_2 = "extra_param_2"

        // ── Operations used by ToolsActivity.kt (File-based, implemented below) ──
        const val OP_IMAGES_TO_PDF = "images_to_pdf"
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

        // ── Operations used by PageEditorViewModel.kt (enqueued directly via
        // its own WorkManager instance/Data, not through enqueue()/enqueuePipeline()) ──
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
         * A single step in a batch pipeline. See [enqueuePipeline].
         */
        data class OperationSpec(
            val operation: String,
            val inputFile: File,
            val outputName: String,
            val extraParam: String? = null,
            val extraParam2: String? = null
        )

        private fun buildInputData(operation: String, inputFile: File, outputName: String, extraParam: String?, extraParam2: String? = null): Data {
            val outputFile = File(inputFile.parentFile ?: inputFile, "$outputName.pdf")
            return Data.Builder()
                .putString(KEY_OPERATION_TYPE, operation)
                .putStringArray(KEY_INPUT_URIS, arrayOf(inputFile.absolutePath))
                .putString(KEY_OUTPUT_URI, outputFile.absolutePath)
                .putString(KEY_OPTIONS, extraParam)
                .putString(KEY_PAGE_RANGE, extraParam)
                .putString(KEY_PASSWORD, extraParam)
                .putString(KEY_EXTRA_PARAM_2, extraParam2)
                .build()
        }

        /**
         * Enqueue a single PDF operation as a unique WorkManager job.
         * Returns the [UUID] of the enqueued work, which callers can observe via
         * `WorkManager.getInstance(context).getWorkInfoByIdLiveData(id)`.
         */
        fun enqueue(
            context: Context,
            operation: String,
            inputFile: File,
            outputName: String,
            extraParam: String? = null,
            extraParam2: String? = null
        ): UUID {
            val inputData = buildInputData(operation, inputFile, outputName, extraParam, extraParam2)
            val request = OneTimeWorkRequestBuilder<PdfOperationWorker>()
                .setInputData(inputData)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "pdf_op_${System.currentTimeMillis()}",
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }

        /**
         * Chain multiple operations to run in order. Returns the id of the final
         * step, or null if [specs] is empty.
         */
        fun enqueuePipeline(context: Context, specs: List<OperationSpec>): UUID? {
            if (specs.isEmpty()) return null
            val requests = specs.map { spec ->
                val inputData = buildInputData(spec.operation, spec.inputFile, spec.outputName, spec.extraParam, spec.extraParam2)
                OneTimeWorkRequestBuilder<PdfOperationWorker>()
                    .setInputData(inputData)
                    .addTag(WORK_TAG)
                    .build()
            }
            var continuation = WorkManager.getInstance(context).beginWith(requests.first())
            for (i in 1 until requests.size) {
                continuation = continuation.then(requests[i])
            }
            continuation.enqueue()
            return requests.last().id
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val operation = inputData.getString(KEY_OPERATION_TYPE) ?: return Result.failure()
            val inputPaths = inputData.getStringArray(KEY_INPUT_URIS) ?: emptyArray()
            val outputPath = inputData.getString(KEY_OUTPUT_URI) ?: return Result.failure()
            val param = inputData.getString(KEY_OPTIONS)

            val primaryInput = inputPaths.firstOrNull()?.let { File(it) } ?: return Result.failure()
            val outputFile = File(outputPath)
            val param2 = inputData.getString(KEY_EXTRA_PARAM_2)

            val opResult: kotlin.Result<File> = when (operation) {
                OP_MERGE -> {
                    val allPaths = param?.split(",")?.filter { it.isNotBlank() }
                        ?: inputPaths.toList().ifEmpty { listOf(primaryInput.absolutePath) }
                    pdfOperationsManager.mergePdfs(allPaths.map { File(it) }, outputFile)
                }
                OP_IMAGES_TO_PDF -> {
                    val allPaths = param?.split(",")?.filter { it.isNotBlank() }
                        ?: inputPaths.toList().ifEmpty { listOf(primaryInput.absolutePath) }
                    pdfOperationsManager.imagesToPdf(allPaths.map { File(it) }, outputFile)
                }
                OP_SPLIT -> {
                    val parts = param?.split("-")
                    val start = parts?.getOrNull(0)?.toIntOrNull()
                    val end = parts?.getOrNull(1)?.toIntOrNull()
                    val ranges = if (start != null && end != null) listOf(start..end) else emptyList()
                    val outDir = outputFile.parentFile ?: outputFile
                    pdfOperationsManager.splitPdf(primaryInput, outDir, ranges)
                        .map { files -> files.firstOrNull() ?: outputFile }
                }
                OP_COMPRESS -> pdfOperationsManager.compressPdf(primaryInput, outputFile, param?.toIntOrNull() ?: 6)
                OP_ENCRYPT -> {
                    val pw = param ?: ""
                    pdfOperationsManager.encryptPdf(primaryInput, outputFile, pw, pw)
                }
                OP_DECRYPT -> pdfOperationsManager.removePdfPassword(primaryInput, outputFile, param ?: "")
                OP_WATERMARK -> pdfOperationsManager.addTextWatermark(
                    primaryInput, outputFile, param ?: "", param2?.toFloatOrNull() ?: 0.3f
                )
                OP_ROTATE -> {
                    val degrees = param?.toIntOrNull() ?: 90
                    val pageCount = param2?.toIntOrNull()
                        ?: (pdfOperationsRepository.getPageCount(android.net.Uri.fromFile(primaryInput))
                            as? com.propdf.core.domain.result.AppResult.Success)?.data
                        ?: 1
                    val rotations = (1..pageCount).associateWith { degrees }
                    pdfOperationsManager.rotatePages(primaryInput, outputFile, rotations)
                }
                OP_DELETE_PAGES -> {
                    val pages = inputData.getIntArray(KEY_PAGE_NUMBERS)?.toList()
                        ?: param?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                        ?: emptyList()
                    pdfOperationsManager.deletePages(primaryInput, outputFile, pages)
                }
                OP_PAGE_NUMBERS -> pdfOperationsManager.addPageNumbers(primaryInput, outputFile)
                OP_HEADER_FOOTER -> pdfOperationsManager.addHeaderFooter(primaryInput, outputFile, param, param2)
                else -> kotlin.Result.failure(UnsupportedOperationException("Operation not implemented: $operation"))
            }

            opResult.fold(
                onSuccess = { file ->
                    Result.success(
                        Data.Builder()
                            .putString(KEY_RESULT_URI, file.absolutePath)
                            .putString(KEY_OUTPUT_URI, file.absolutePath)
                            .build()
                    )
                },
                onFailure = { e ->
                    Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR_MESSAGE, e.message ?: "Operation failed")
                            .build()
                    )
                }
            )
        } catch (e: Exception) {
            Result.failure(
                Data.Builder().putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error").build()
            )
        }
    }
}
