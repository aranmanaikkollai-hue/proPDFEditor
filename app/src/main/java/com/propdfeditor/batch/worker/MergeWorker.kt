package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class MergeWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val inputUris = job.inputUris
        val outputUri = job.outputUri ?: throw IllegalStateException("Output URI required")

        updateProgress(0, 0, inputUris.size)

        return withContext(Dispatchers.IO) {
            try {
                pdfProcessor.mergePdfs(
                    context = applicationContext,
                    inputUris = inputUris,
                    outputUri = outputUri,
                    onProgress = { processed ->
                        val progress = (processed * 100) / inputUris.size
                        updateProgress(progress, processed, inputUris.size)
                    }
                )

                updateProgress(100, inputUris.size, inputUris.size)
                workDataOf("output_uri" to outputUri.toString())
            } catch (e: Exception) {
                Timber.e(e, "Merge failed")
                throw e
            }
        }
    }
}
