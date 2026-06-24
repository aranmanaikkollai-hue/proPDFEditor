package com.propdf.editor.data.worker

import android.content.Context
import androidx.startup.Initializer

class WorkManagerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        DocumentIndexWorker.schedule(context)
        DuplicateScanWorker.schedulePeriodic(context)
        SmartFolderRefreshWorker.schedule(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
