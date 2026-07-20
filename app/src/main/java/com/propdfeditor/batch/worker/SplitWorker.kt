package com.propdfeditor.batch.worker

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class SplitWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class SplitConfig(
        val pageRanges: List<String>? = null, // e.g., ["1-3", "4-6"]
        val splitEvery: Int? = null, // split every N pages
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val inputUri = job.inputUris.firstOrNull() ?: throw IllegalStateException("Input URI required")
        val config = Gson().fromJson(job.configJson, SplitConfig::class.java)
        val outputDirUri = android.net.Uri.parse(config.outputDirUri)

        return withContext(Dispatchers.IO) {
            try {
                val resultUris = pdfProcessor.splitPdf(
                    context = applicationContext,
                    inputUri = inputUri,
                    outputDirUri = outputDirUri,
                    pageRanges = config.pageRanges,
                    splitEvery = config.splitEvery,
                    onProgress = { current, total ->
                        val progress = if (total > 0) (current * 100) / total else 0
                        updateProgress(progress, current, total)
                    }
                )

                updateProgress(100, resultUris.size, resultUris.size)
                workDataOf(
                    "output_uris" to Gson().toJson(resultUris.map { it.toString() }),
                    "count" to resultUris.size
                )
            } catch (e: Exception) {
                Timber.e(e, "Split failed")
                throw e
            }
        }
    }
}
