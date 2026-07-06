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

/**
 * WorkManager worker for background batch OCR processing.
 * Survives app kills, device reboots, and Doze mode.
 */
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
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing job ID")
        )

        val imageUrisJson = inputData.getString(KEY_IMAGE_URIS) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing image URIs")
        )
        val languageCodesJson = inputData.getString(KEY_LANGUAGE_CODES) ?: "[]"
        val outputFormat = inputData.getString(KEY_OUTPUT_FORMAT) ?: "TEXT"

        val imageUris = gson.fromJson(imageUrisJson, Array<String>::class.java).toList()
        val languageCodes = gson.fromJson(languageCodesJson, Array<String>::class.java).toList()
        val languages = languageCodes.map { OcrLanguage.fromCode(it) }

        try {
            // Update status to preprocessing
            updateJobStatus(jobId, OcrJobStatus.PREPROCESSING, 0, imageUris.size, 0)

            val config = OcrConfig(
                languages = languages,
                outputFormat = OcrOutputFormat.valueOf(outputFormat)
            )

            val results = mutableListOf<OcrPageResult>()

            // Process each image
            imageUris.forEachIndexed { index, uriStr ->
                // Check for cancellation
                if (isStopped) {
                    updateJobStatus(jobId, OcrJobStatus.CANCELLED)
                    return Result.failure(workDataOf(KEY_ERROR to "Cancelled by user"))
                }

                val progress = PROGRESS_PREPROCESSING + (index * (PROGRESS_RECOGNIZING - PROGRESS_PREPROCESSING) / imageUris.size)
                setProgress(workDataOf(KEY_PROGRESS to progress))
                updateJobStatus(jobId, OcrJobStatus.RECOGNIZING, progress, imageUris.size, index)

                val uri = android.net.Uri.parse(uriStr)
                when (val result = ocrRepository.recognizeImageUri(uri, config)) {
                    is AppResult.Success -> {
                        results.add(result.data)
                    }
                    is AppResult.Error -> {
                        updateJobStatus(jobId, OcrJobStatus.FAILED, error = result.message)
                        return Result.failure(workDataOf(KEY_ERROR to (result.message ?: "OCR failed")))
                    }
                    is AppResult.Loading -> { /* no-op */ }
                }
            }

            // Apply correction if needed
            updateJobStatus(jobId, OcrJobStatus.CORRECTING, PROGRESS_CORRECTING, imageUris.size, imageUris.size)
            val correctedResults = results.map { page ->
                when (val corrected = ocrRepository.correctText(page.fullText, languages.firstOrNull() ?: OcrLanguage.ENGLISH)) {
                    is AppResult.Success -> page.copy(fullText = corrected.data)
                    else -> page
                }
           
