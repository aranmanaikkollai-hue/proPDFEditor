// security/src/main/java/com/propdf/security/notification/SecurityNotificationManager.kt
package com.propdf.security.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.propdf.security.R

class SecurityNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "security_operations"
        const val CHANNEL_NAME = "Security Operations"
        const val NOTIFICATION_ID = 1001
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for document security operations"
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgressNotification(title: String, message: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_security)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showCompletionNotification(title: String, message: String, success: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (success) R.drawable.ic_check else R.drawable.ic_error)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
