package com.propdf.editor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.propdf.editor.data.worker.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule background work after reboot
            workScheduler.scheduleDocumentScan()
            workScheduler.scheduleStorageAnalyzer()
        }
    }
}
