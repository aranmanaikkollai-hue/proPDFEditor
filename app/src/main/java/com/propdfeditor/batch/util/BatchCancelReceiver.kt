package com.propdfeditor.batch.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BatchCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: BatchJobRepository

    @Inject
    lateinit var notificationManager: BatchNotificationManager

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra("job_id", -1L)
        val workRequestId = intent.getStringExtra("work_request_id")

        if (jobId != -1L) {
            CoroutineScope(Dispatchers.IO).launch {
                repository.cancelJob(jobId)
                notificationManager.cancelNotification(jobId)
            }
        }

        workRequestId?.let {
            WorkManager.getInstance(context).cancelWorkById(java.util.UUID.fromString(it))
        }
    }
}
