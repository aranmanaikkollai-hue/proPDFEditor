package com.propdf.editor.data.worker

import android.content.Context
import androidx.startup.Initializer
import com.propdf.editor.data.index.DocumentIndexWorker
import com.propdfeditor.batch.RecycleBinCleanupWorker

// Intentionally NOT registered in the manifest (project decision: left as is for now). The lint finding
// EnsureInitializerMetadata for this file is ignored per path in app/lint.xml (an annotation does not work: the
// check reports at file level), so that a real, new lint error is not hidden by this known one.
// Note: this means the schedules in the legacy copy (document index, duplicate scan, smart folders, backfill,
// recycle-bin cleanup) are not started by this class.
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
