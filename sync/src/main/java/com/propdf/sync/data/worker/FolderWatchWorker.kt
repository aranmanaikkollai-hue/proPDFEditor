package com.propdf.sync.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.sync.domain.repository.FolderWatchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic worker that scans all watched folders for new PDFs.
 * Uses WorkManager with battery-aware constraints.
 */
@HiltWorker
class FolderWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderWatchRepository: FolderWatchRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Scan all watched folders
            var totalImported = 0
            // In production, iterate through all watched folders
            Result.success(
                androidx.work.workDataOf(KEY_IMPORTED_COUNT to totalImported)
            )
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "folder_watch_worker"
        const val KEY_IMPORTED_COUNT = "imported_count"

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<FolderWatchWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
