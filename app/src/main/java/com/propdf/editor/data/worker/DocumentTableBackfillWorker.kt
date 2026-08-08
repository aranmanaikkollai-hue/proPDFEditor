package com.propdf.editor.data.worker

import android.content.Context
import android.content.SharedPreferences
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-time migration worker: core.ProPDFDatabase's pdf_documents table had no
 * writer before RecentFilesRepositoryImpl's add()/setFavourite() dual-write
 * was added. That dual-write only covers files opened/scanned *after* the
 * fix ships — existing users' history (already sitting in
 * core.RecentFilesDatabase) would otherwise never appear in pdf_documents,
 * meaning Home's recent-files widget, Duplicate Finder, Storage Analyzer,
 * and Recent Activity would look empty for them until they happened to
 * re-open every old file individually.
 *
 * This runs once per install (guarded by a SharedPreferences flag — the
 * underlying sync is idempotent and safe to repeat regardless, so the flag
 * is purely to avoid redundant work, not a correctness requirement).
 */
@HiltWorker
class DocumentTableBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recentFilesRepository: RecentFilesRepository,
    private val logger: AppLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BACKFILL_DONE, false)) {
                return Result.success(workDataOf("skipped" to true))
            }

            val synced = recentFilesRepository.backfillDocumentTable()
            prefs.edit().putBoolean(KEY_BACKFILL_DONE, true).apply()

            logger.i("DocumentTableBackfillWorker", "Backfilled $synced document(s) into pdf_documents")
            Result.success(workDataOf("synced" to synced))
        } catch (e: Exception) {
            logger.e("DocumentTableBackfillWorker", "Backfill failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "document_table_backfill_work"
        private const val PREFS_NAME = "document_table_backfill_prefs"
        private const val KEY_BACKFILL_DONE = "backfill_v1_done"

        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<DocumentTableBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
