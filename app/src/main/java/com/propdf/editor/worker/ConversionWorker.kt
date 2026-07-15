package com.propdf.editor.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.propdf.editor.data.repository.ConversionRepository
import com.propdf.editor.domain.model.ConversionResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ConversionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ConversionRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_TAG = "conversion_worker"
        const val KEY_TASK_ID = "task_id"
        const val KEY_CONVERSION_TYPE = "conversion_type"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_SOURCE_URIS = "source_uris"
        
        const val KEY_RESULT_SUCCESS = "result_success"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_MESSAGE = "result_message"
        const val KEY_RESULT_FILE_COUNT = "result_file_count"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID)
            ?: return@withContext Result.failure(
                Data.Builder().putString("error", "Missing task ID").build()
            )

        try {
            setProgressAsync(
                Data.Builder()
                    .putString(KEY_TASK_ID, taskId)
                    .putInt("progress", 0)
                    .build()
            ).await()

            val result = repository.executeTask(taskId)

            val outputData = Data.Builder()
                .putBoolean(KEY_RESULT_SUCCESS, result.success)
                .putString(KEY_RESULT_URI, result.outputUri?.toString())
                .putString(KEY_RESULT_MESSAGE, result.message)
                .putInt(KEY_RESULT_FILE_COUNT, result.fileCount)
                .build()

            if (result.success) {
                Result.success(outputData)
            } else {
                Result.failure(outputData)
            }

        } catch (e: Exception) {
            Result.failure(
                Data.Builder()
                    .putString("error", e.message ?: "Worker failed")
                    .build()
            )
        }
    }

    private suspend fun updateProgress(taskId: String, progress: Int) {
        setProgressAsync(
            Data.Builder()
                .putString(KEY_TASK_ID, taskId)
                .putInt("progress", progress)
                .build()
        ).await()
    }
}
