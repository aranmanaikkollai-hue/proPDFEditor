package com.propdf.editor

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.propdfeditor.core.worker.SignatureCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ProPDFApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        setupPeriodicCleanup()
    }

    private fun setupPeriodicCleanup() {
        val cleanupRequest = PeriodicWorkRequestBuilder<SignatureCleanupWorker>(
            7, TimeUnit.DAYS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SignatureCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
    }
}
