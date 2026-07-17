package com.propdf.core.data.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltWorker
class CompressionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CompressionRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_CONFIG = "config_json"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT = "result_json"
        const val KEY_ERROR = "error_message"

        fun createInputData(
            inputUri: Uri,
            outputUri: Uri,
            config: CompressionConfig
        ): Data = workDataOf(
            KEY_INPUT_URI to inputUri.toString(),
            KEY_OUTPUT_URI to outputUri.toString(),
            KEY_CONFIG to Json.encodeToString(config)
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing input URI")
        )
        val outputUri = inputData.getString(KEY_OUTPUT_URI) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing output URI")
        )
        val configJson = inputData.getString(KEY_CONFIG) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing configuration")
        )
        
        val config = try {
            json.decodeFromString<CompressionConfig>(configJson)
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to "Invalid configuration"))
        }

        return try {
            var finalResult: CompressionResult? = null
            
            repository.compress(inputUri, outputUri, config).collect { result ->
                when (result) {
                    is AppResult.Loading -> {
                        setProgress(workDataOf(KEY_PROGRESS to result.progress))
                    }
                    is AppResult.Success -> {
                        finalResult = result.data
                    }
                    is AppResult.Error -> {
                        throw Exception(result.exception.message)
                    }
                }
            }

            finalResult?.let {
                Result.success(workDataOf(
                    KEY_RESULT to json.encodeToString(it),
                    KEY_PROGRESS to 1.0f
                ))
            } ?: Result.failure(workDataOf(KEY_ERROR to "No result produced"))

        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }
}
