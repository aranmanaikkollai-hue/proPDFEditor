package com.propdfeditor.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdfeditor.core.repository.SignatureHistoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SignatureCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val historyRepository: SignatureHistoryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Clear history older than 90 days
            val ninetyDaysInMillis = 90L * 24 * 60 * 60 * 1000
            historyRepository.clearOldHistory(ninetyDaysInMillis)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "signature_cleanup_worker"
    }
}
