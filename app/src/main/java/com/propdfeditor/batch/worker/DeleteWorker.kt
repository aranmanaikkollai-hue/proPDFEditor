package com.propdfeditor.batch.worker

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class DeleteWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager
) : BaseBatchWorker(context, params, repository, notificationManager) {

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val inputUris = job.inputUris

        return withContext(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0

            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                try {
                    val documentFile = DocumentFile.fromSingleUri(applicationContext, uri)
                    if (documentFile != null && documentFile.exists()) {
                        if (documentFile.delete()) {
                            successCount++
                        } else {
                            failCount++
                        }
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Delete failed for $uri")
                    failCount++
                }

                val progress = ((index + 1) * 100) / inputUris.size
                updateProgress(progress, index + 1, inputUris.size)
            }

            workDataOf(
                "success_count" to successCount,
                "fail_count" to failCount
            )
        }
    }
}
