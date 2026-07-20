package com.propdfeditor.batch.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.propdfeditor.R
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID_BATCH_PROGRESS = "batch_progress"
        const val CHANNEL_ID_BATCH_COMPLETE = "batch_complete"
        const val NOTIFICATION_ID_OFFSET = 10000
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID_BATCH_PROGRESS,
                context.getString(R.string.batch_progress_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.batch_progress_channel_desc)
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                CHANNEL_ID_BATCH_COMPLETE,
                context.getString(R.string.batch_complete_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.batch_complete_channel_desc)
            }

            notificationManager.createNotificationChannels(listOf(progressChannel, completeChannel))
        }
    }

    fun createForegroundInfo(job: BatchJobEntity): ForegroundInfo {
        val notificationId = job.notificationId + NOTIFICATION_ID_OFFSET
        val notification = buildProgressNotification(job, 0, 0, job.totalItems)
        return ForegroundInfo(notificationId, notification)
    }

    fun updateProgress(jobId: Long, progress: Int, processed: Int, total: Int) {
        val notificationId = (jobId + NOTIFICATION_ID_OFFSET).toInt()
        // Note: Actual notification update requires fetching job from DB
        // This is handled by observing WorkManager progress in the ViewModel
    }

    fun showProgressNotification(job: BatchJobEntity, progress: Int, processed: Int, total: Int) {
        val notificationId = job.notificationId + NOTIFICATION_ID_OFFSET
        val notification = buildProgressNotification(job, progress, processed, total)
        notificationManager.notify(notificationId, notification)
    }

    fun showCompletionNotification(job: BatchJobEntity, success: Boolean, message: String? = null) {
        val notificationId = job.notificationId + NOTIFICATION_ID_OFFSET + 1
        
        val title = if (success) {
            context.getString(R.string.batch_complete_title, getJobTypeName(job.type))
        } else {
            context.getString(R.string.batch_failed_title, getJobTypeName(job.type))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_BATCH_COMPLETE)
            .setSmallIcon(if (success) R.drawable.ic_check_circle else R.drawable.ic_error)
            .setContentTitle(title)
            .setContentText(message ?: context.getString(R.string.batch_complete_message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)

        // Add open action
        val openIntent = Intent(context, Class.forName("com.propdfeditor.ui.batch.BatchActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("job_id", job.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun buildProgressNotification(
        job: BatchJobEntity,
        progress: Int,
        processed: Int,
        total: Int
    ): android.app.Notification {
        val notificationId = job.notificationId + NOTIFICATION_ID_OFFSET
        
        val title = context.getString(
            R.string.batch_progress_title,
            getJobTypeName(job.type),
            processed,
            total
        )

        val cancelIntent = Intent(context, BatchCancelReceiver::class.java).apply {
            putExtra("job_id", job.id)
            putExtra("work_request_id", job.workRequestId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_BATCH_PROGRESS)
            .setSmallIcon(R.drawable.ic_batch_processing)
            .setContentTitle(title)
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_cancel, context.getString(R.string.cancel), cancelPendingIntent)
            .build()
    }

    private fun getJobTypeName(type: BatchJobType): String {
        return when (type) {
            BatchJobType.MERGE -> context.getString(R.string.batch_merge)
            BatchJobType.SPLIT -> context.getString(R.string.batch_split)
            BatchJobType.RENAME -> context.getString(R.string.batch_rename)
            BatchJobType.WATERMARK -> context.getString(R.string.batch_watermark)
            BatchJobType.ROTATE -> context.getString(R.string.batch_rotate)
            BatchJobType.COMPRESS -> context.getString(R.string.batch_compress)
            BatchJobType.OCR -> context.getString(R.string.batch_ocr)
            BatchJobType.ENCRYPT -> context.getString(R.string.batch_encrypt)
            BatchJobType.DECRYPT -> context.getString(R.string.batch_decrypt)
            BatchJobType.EXPORT -> context.getString(R.string.batch_export)
            BatchJobType.DELETE -> context.getString(R.string.batch_delete)
        }
    }

    fun cancelNotification(jobId: Long) {
        val notificationId = (jobId + NOTIFICATION_ID_OFFSET).toInt()
        notificationManager.cancel(notificationId)
        notificationManager.cancel(notificationId + 1)
    }
}
