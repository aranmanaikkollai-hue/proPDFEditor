package com.propdfeditor.batch

import android.content.Context
import android.net.Uri
import androidx.lifecycle.asFlow
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchOperationsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun enqueueCompress(
        uris: List<Uri>,
        quality: Float = 0.7f
    ): Flow<BatchProgress> {
        val inputData = workDataOf(
            BatchOperationsWorker.KEY_OPERATION to BatchOperationsWorker.OPERATION_COMPRESS,
            BatchOperationsWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray(),
            BatchOperationsWorker.KEY_QUALITY to quality
        )

        val request = OneTimeWorkRequestBuilder<BatchOperationsWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "batch_compress_${System.currentTimeMillis()}",
            ExistingWorkPolicy.REPLACE,
            request
        )

        return observeProgress(request.id)
    }

    fun enqueueOcr(uris: List<Uri>): Flow<BatchProgress> {
        val inputData = workDataOf(
            BatchOperationsWorker.KEY_OPERATION to BatchOperationsWorker.OPERATION_OCR,
            BatchOperationsWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray()
        )

        val request = OneTimeWorkRequestBuilder<BatchOperationsWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return observeProgress(request.id)
    }

    fun enqueueMerge(uris: List<Uri>): Flow<BatchProgress> {
        val inputData = workDataOf(
            BatchOperationsWorker.KEY_OPERATION to BatchOperationsWorker.OPERATION_MERGE,
            BatchOperationsWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray()
        )

        val request = OneTimeWorkRequestBuilder<BatchOperationsWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return observeProgress(request.id)
    }

    fun scheduleRecycleBinCleanup() {
        val request = PeriodicWorkRequestBuilder<RecycleBinCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "recycle_bin_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun observeProgress(workId: UUID): Flow<BatchProgress> {
        return WorkManager.getInstance(context)
            .getWorkInfoByIdLiveData(workId)
            .asFlow()
            .map { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt("progress", 0)
                        val total = workInfo.progress.getInt("total", 1)
                        BatchProgress.Running(progress, total)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val results = workInfo.outputData.getStringArray(BatchOperationsWorker.KEY_RESULT_URIS)
                        BatchProgress.Success(results?.toList() ?: emptyList())
                    }
                    WorkInfo.State.FAILED -> BatchProgress.Failed
                    WorkInfo.State.CANCELLED -> BatchProgress.Cancelled
                    else -> BatchProgress.Pending
                }
            }
    }
}

sealed interface BatchProgress {
    data object Pending : BatchProgress
    data class Running(val current: Int, val total: Int) : BatchProgress
    data class Success(val resultUris: List<String>) : BatchProgress
    data object Failed : BatchProgress
    data object Cancelled : BatchProgress
}
