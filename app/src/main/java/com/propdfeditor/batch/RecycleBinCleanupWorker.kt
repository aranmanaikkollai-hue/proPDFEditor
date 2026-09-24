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
import com.propdf.editor.data.local.dao.PdfDocumentDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Permanently deletes files that have been sitting in the recycle bin longer than the
 * user's "Auto-delete from recycle bin" setting (Settings screen, [SettingsDataStore.autoDeleteDays],
 * 1-90 days).
 *
 * NOTE: this previously deleted from `RecycleBinDao`/`recycle_bin`, a separate Room entity/table
 * that nothing in the app ever inserts into -- so this worker ran daily and cleaned up nothing,
 * while the *real* recycle bin (soft-deleted rows in `pdf_documents`, isDeleted/deletedAt, as
 * used by RecycleBinViewModel / the actual Recycle Bin screen) was never auto-purged at all,
 * regardless of what the user set the slider to. Repointed at the real table via the DAO method
 * that already existed for this (`permanentDeleteOld`) but was never called from anywhere.
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
            val autoDeleteDays = settingsDataStore.autoDeleteDays.first()
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(autoDeleteDays.toLong())
            pdfDocumentDao.permanentDeleteOld(cutoff)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "recycle_bin_cleanup"

        /**
         * Schedules the same unique periodic work that [com.propdfeditor.batch.BatchOperationsManager
         * .scheduleRecycleBinCleanup] enqueues from BootReceiver -- added here too so cleanup is
         * also scheduled on ordinary app startup, not only after a device reboot. Same unique work
         * name + KEEP policy, so this is a no-op if the boot-time schedule already ran.
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
