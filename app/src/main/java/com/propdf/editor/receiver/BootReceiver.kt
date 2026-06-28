package com.propdf.editor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.propdf.backup.data.scheduler.BackupWorker
import com.propdf.backup.domain.model.BackupConfig
import com.propdf.backup.domain.repository.BackupRepository
import com.propdf.sync.data.worker.FolderWatchWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-schedules background work after device reboot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    var backupRepository: BackupRepository? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            scope.launch {
                // Re-schedule backup if enabled and repository is available
                backupRepository?.let { repo ->
                    try {
                        val config = repo.getConfig().first()
                        if (config.isEnabled && config.scheduleFrequency != com.propdf.backup.domain.model.BackupFrequency.MANUAL) {
                            BackupWorker.schedule(context, config)
                        }
                    } catch (_: Exception) {
                        // Backup repository not fully wired — skip silently
                    }
                }

                // Re-schedule folder watch
                try {
                    FolderWatchWorker.schedule(context)
                } catch (_: Exception) {
                    // Folder watch not fully wired — skip silently
                }
            }
        }
    }
}
