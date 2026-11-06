package com.propdfeditor.batch

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.propdf.editor.data.local.SettingsDataStore
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.repository.RecycleBinPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Purges recycle-bin DATABASE ROWS that have been in the bin longer than the user's "Auto-delete from recycle bin"
 * setting ([SettingsDataStore.autoDeleteDays], clamped to 1-90 days). Original files are never touched.
 *
 * RETARGETED: this now purges the :core bin table (see docs/RECYCLE_BIN_DATA_FLOW.md); the `:app` database's
 * pdf_documents is never soft-deleted into by any active path, so the previous query could not match real bin items.
 */
@HiltWorker
class RecycleBinCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfDocumentDao: PdfDocumentDao,
    private val settingsDataStore: SettingsDataStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val autoDeleteDays = RecycleBinPolicy.clampAutoDeleteDays(settingsDataStore.autoDeleteDays.first())
            val cutoff = RecycleBinPolicy.cutoffMillis(System.currentTimeMillis(), autoDeleteDays)
            // Authoritative bin = :core ProPDFDatabase.pdf_documents (the table the bin screen, delete and restore use).
            // Row purge only: original files are never touched, and rows without deleted_at never match (SQL NULL).
            pdfDocumentDao.deleteOldRecycleBinItems(cutoff)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "recycle_bin_cleanup"

        /**
         * Same unique periodic work that BatchOperationsManager.scheduleRecycleBinCleanup() enqueues from the
         * registered BootReceiver (same unique name + KEEP policy). In the current source this function is only
         * called from the legacy, UNREGISTERED WorkManagerInitializer, so the only live schedule is the boot one.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
