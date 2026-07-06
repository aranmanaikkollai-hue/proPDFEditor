package com.propdf.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.data.local.OcrJobEntity
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ocrRepository: OcrRepository,
    private val ocrJobDao: OcrJobDao
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_IMAGE_URIS = "image_uris"
        const val KEY_LANGUAGE_CODES = "language_codes"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val PROGRESS_PREPROCESSING = 10
        const val PROGRESS_RECOGNIZING = 50
        const val PROGRESS_CORRECTING = 80
        const val PROGRESS_EXPORTING = 95
    }

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure(workDataOf(KEY_ERROR to "Missing job ID"))
        val imageUrisJson = inputData.getString(KEY_IMAGE_URIS) ?: return Result.failure(workDataOf(KEY_ERROR to "Missing image URIs"))
        val languageCodesJson = inputData.getString(KEY_LANGUAGE_CODES) ?: "[]"
        val outputFormat = inputData.getString(KEY_OUTPUT_FORMAT) ?: "TEXT"
        val imageUris = gson.fromJson(imageUrisJson, Array<String>::class.java).toList()
        val languageCodes = gson.fromJson(languageCodesJson, Array<String>::class.java).toList()
        val languages = languageCodes.map { OcrLanguage.fromCode(it) }

        try {
            updateJobStatus(jobId, OcrJobStatus.PREPROCESSING, 0, imageUris.size, 0)
            val config = OcrConfig(languages = languages, outputFormat = OcrOutputFormat.valueOf(outputFormat))
            val results = mutableListOf<OcrPageResult>()

            imageUris.forEachIndexed { index, uriStr ->
                if (isStopped) { updateJobStatus(jobId, OcrJobStatus.CANCELLED); return Result.failure(workDataOf(KEY_ERROR to "Cancelled")) }
                val progress = PROGRESS_PREPROCESSING + (index * (PROGRESS_RECOGNIZING - PROGRESS_PREPROCESSING) / imageUris.size)
                setProgress(workDataOf(KEY_PROGRESS to progress))
                updateJobStatus(jobId, OcrJobStatus.RECOGNIZING, progress, imageUris.size, index)
                val uri = android.net.Uri.parse(uriStr)
                when (val result = ocrRepository.recognizeImageUri(uri, config)) {
                    is AppResult.Success -> results.add(result.data)
                    is AppResult.Error -> { updateJobStatus(jobId, OcrJobStatus.FAILED, error = result.message); return Result.failure(workDataOf(KEY_ERROR to (result.message ?: "OCR failed"))) }
                    is AppResult.Loading -> {}
                }
            }

            updateJobStatus(jobId, OcrJobStatus.CORRECTING, PROGRESS_CORRECTING, imageUris.size, imageUris.size)
            val correctedResults = results.map { page ->
                when (val corrected = ocrRepository.correctText(page.fullText, languages.firstOrNull() ?: OcrLanguage.ENGLISH)) {
                    is AppResult.Success -> page.copy(fullText = corrected.data)
                    else -> page
                }
            }

            updateJobStatus(jobId, OcrJobStatus.EXPORTING, PROGRESS_EXPORTING, imageUris.size, imageUris.size)
            val outputFileName = "ocr_result_${System.currentTimeMillis()}.${getExtension(config.outputFormat)}"
            val outputUri = createOutputUri(outputFileName)
            val exportResult = when (config.outputFormat) {
                OcrOutputFormat.PDF -> ocrRepository.exportToPdf(correctedResults, outputUri)
                OcrOutputFormat.TXT -> ocrRepository.exportToTxt(correctedResults, outputUri)
                OcrOutputFormat.DOCX -> ocrRepository.exportToDocx(correctedResults, outputUri)
                OcrOutputFormat.TEXT -> ocrRepository.exportToTxt(correctedResults, outputUri)
            }

            return when (exportResult) {
                is AppResult.Success -> {
                    updateJobStatus(jobId, OcrJobStatus.COMPLETED, 100, imageUris.size, imageUris.size, resultUri = outputUri.toString())
                    Result.success(workDataOf(KEY_RESULT_URI to outputUri.toString(), KEY_PROGRESS to 100))
                }
                is AppResult.Error -> { updateJobStatus(jobId, OcrJobStatus.FAILED, error = exportResult.message); Result.failure(workDataOf(KEY_ERROR to (exportResult.message ?: "Export failed"))) }
                is AppResult.Loading -> Result.failure(workDataOf(KEY_ERROR to "Unexpected loading"))
            }
        } catch (e: Exception) {
            updateJobStatus(jobId, OcrJobStatus.FAILED, error = e.message)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun updateJobStatus(jobId: String, status: OcrJobStatus, progress: Int = 0, totalPages: Int = 0, completedPages: Int = 0, resultUri: String? = null, error: String? = null) {
        val entity = ocrJobDao.getById(jobId) ?: return
        ocrJobDao.update(entity.copy(status = status, progress = progress, totalPages = totalPages, completedPages = completedPages,
            resultUri = resultUri, errorMessage = error, completedAt = if (status == OcrJobStatus.COMPLETED) System.currentTimeMillis() else entity.completedAt))
    }

    private fun createOutputUri(fileName: String): android.net.Uri {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val file = java.io.File(downloadsDir, "ProPDF/OCR/$fileName")
        file.parentFile?.mkdirs()
        return android.net.Uri.fromFile(file)
    }

    private fun getExtension(format: OcrOutputFormat): String = when (format) {
        OcrOutputFormat.PDF -> "pdf"; OcrOutputFormat.TXT -> "txt"; OcrOutputFormat.DOCX -> "docx"; OcrOutputFormat.TEXT -> "txt"
    }
}
