package com.propdf.editor.feature.forms.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.engine.PdfFormEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class FlattenFormWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: PdfFormEngine
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_ERROR = "error_message"
    }

    override suspend fun doWork(): Result {
        val inputUriStr = inputData.getString(KEY_INPUT_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing input URI"))
        val outputPath = inputData.getString(KEY_OUTPUT_PATH)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing output path"))

        return try {
            val inputFile = File(Uri.parse(inputUriStr).path!!)
            val outputFile = File(outputPath)

            when (val result = engine.flattenForm(inputFile, outputFile)) {
                is AppResult.Success -> {
                    Result.success(workDataOf(KEY_RESULT_URI to outputFile.toURI().toString()))
                }
                is AppResult.Error -> {
                    Result.failure(workDataOf(KEY_ERROR to (result.message ?: "Unknown error")))
                }
                is AppResult.Loading -> Result.failure(workDataOf(KEY_ERROR to "Unexpected result"))
            }
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to e.message))
        }
    }
}
