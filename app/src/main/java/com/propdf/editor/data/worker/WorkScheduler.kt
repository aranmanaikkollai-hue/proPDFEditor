package com.propdf.editor.data.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    fun scheduleDocumentScan() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DocumentScanWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(DocumentScanWorker.WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DocumentScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleDuplicateFinder() {
        val request = OneTimeWorkRequestBuilder<DuplicateFinderWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            DuplicateFinderWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleStorageAnalyzer() {
        val request = PeriodicWorkRequestBuilder<StorageAnalyzerWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            StorageAnalyzerWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelAllWork() {
        workManager.cancelAllWork()
    }
}
