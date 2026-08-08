package com.propdfeditor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.propdfeditor.batch.BatchOperationsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var batchOperationsManager: BatchOperationsManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule periodic tasks after reboot
            batchOperationsManager.scheduleRecycleBinCleanup()
        }
    }
}
