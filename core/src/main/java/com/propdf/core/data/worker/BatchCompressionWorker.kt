package com.propdf.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltWorker
class BatchCompressionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CompressionRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URIS = "uris_json"
        const val KEY_CONFIG = "config_json"
        const val KEY_RESULTS = "results_json"
        const val KEY_PROGRESS = "progress"

        fun createInputData(
            uris: List<Pair<String, String>>, // (input, output) pairs
            config: CompressionConfig
        ): Data = workDataOf(
            KEY_URIS to Json.encodeToString(uris),
            KEY_CONFIG to Json.encodeToString(config)
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val urisJson = inputData.getString(KEY_URIS) ?: return Result.failure()
        val configJson = inputData.getString(KEY_CONFIG) ?: return Result.failure()
        
        val uris = json.decodeFromString<List<List<String>>>(urisJson)
            .map { it[0] to it[1] }
        val config = json.decodeFromString<CompressionConfig>(configJson)
        
        val results = mutableListOf<String>()
        
        uris.forEachIndexed { index, (input, output) ->
            if (isStopped) return Result.failure()
            
            val progress = index.toFloat() / uris.size
            setProgress(workDataOf(KEY_PROGRESS to progress))
            
            try {
                var success = false
                repository.compress(input, output, config).collect { result ->
                    when (result) {
                        is AppResult.Success -> {
                            results.add(json.encodeToString(result.data))
                            success = true
                        }
                        is AppResult.Error -> throw Exception(result.exception.message)
                        else -> {}
                    }
                }
                if (!success) throw Exception("Compression failed")
            } catch (e: Exception) {
                results.add("ERROR: ${e.message}")
            }
        }
        
        return Result.success(workDataOf(
            KEY_RESULTS to json.encodeToString(results),
            KEY_PROGRESS to 1.0f
        ))
    }
}
