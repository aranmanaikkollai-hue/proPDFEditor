package com.propdf.editor.data.worker

import android.content.Context
import androidx.startup.Initializer
import com.propdf.editor.data.index.DocumentIndexWorker
import com.propdfeditor.batch.RecycleBinCleanupWorker

class WorkManagerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        DocumentIndexWorker.schedule(context)
        DuplicateScanWorker.schedulePeriodic(context)
        SmartFolderRefreshWorker.schedule(context)
        DocumentTableBackfillWorker.scheduleOnce(context)
        // Previously only scheduled from BootReceiver on ACTION_BOOT_COMPLETED, so a user who
        // installed the app and never rebooted their device would never get recycle-bin
        // auto-cleanup scheduled at all. Same unique work name + KEEP policy as the boot-time
        // schedule, so this is safe to call on every ordinary app startup too.
        RecycleBinCleanupWorker.schedulePeriodic(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
