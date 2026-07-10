package com.propdf.editor.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class StorageAnalyzerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(applicationContext, params) {

    companion object {
        const val WORK_NAME = "storage_analyzer_worker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val analysis = documentRepository.getStorageAnalysis()
            // Store result in preferences or database for quick access
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
