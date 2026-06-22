package com.propdf.backup.data.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.backup.domain.model.BackupConfig
import com.propdf.backup.domain.model.BackupFrequency
import com.propdf.backup.domain.repository.BackupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar

/**
 * WorkManager worker for scheduled backups.
 * Respects battery and storage constraints.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = inputData.getString(KEY_CONFIG_JSON)
            ?: return Result.failure()

        // Parse config and execute backup
        return try {
            val backupConfig = parseConfig(config)
            if (!backupConfig.isEnabled) {
                return Result.success()
            }

            when (val result = backupRepository.createBackup(backupConfig)) {
                is com.propdf.core.domain.result.AppResult.Success -> Result.success()
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun parseConfig(json: String): BackupConfig {
        // Simplified parsing - use Gson in production
        return BackupConfig()
    }

    companion object {
        const val KEY_CONFIG_JSON = "backup_config_json"
        const val WORK_NAME = "scheduled_backup"

        fun schedule(context: Context, config: BackupConfig) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val repeatInterval = when (config.scheduleFrequency) {
                BackupFrequency.DAILY -> 1L to java.util.concurrent.TimeUnit.DAYS
                BackupFrequency.WEEKLY -> 7L to java.util.concurrent.TimeUnit.DAYS
                BackupFrequency.MONTHLY -> 30L to java.util.concurrent.TimeUnit.DAYS
                else -> return // Manual - don't schedule
            }

            val request = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(
                repeatInterval.first,
                repeatInterval.second
            )
                .setConstraints(constraints)
                .setInputData(
                    androidx.work.workDataOf(KEY_CONFIG_JSON to serializeConfig(config))
                )
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun serializeConfig(config: BackupConfig): String {
            // Use Gson in production
            return "{}"
        }
    }
}
