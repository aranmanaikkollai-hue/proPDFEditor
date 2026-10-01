package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class RotateWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class RotateConfig(
        val rotation: Int = 90, // 90, 180, 270
        val allPages: Boolean = true,
        val pageNumbers: List<Int>? = null, // 1-based
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, RotateConfig::class.java)
        val inputUris = job.inputUris
        val outputDirUri = Uri.parse(config.outputDirUri)
        val outputUris = mutableListOf<String>()

        return withContext(Dispatchers.IO) {
            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                var outputFile: Uri? = null
                try {
                    outputFile = createOutputFile(outputDirUri, uri, "_rotated")
                        ?: throw IllegalStateException("Cannot create output file")

                    val pages = if (config.allPages) null else (config.pageNumbers ?: emptyList())
                    pdfProcessor.rotatePdf(applicationContext, uri, outputFile, config.rotation, pages)
                    outputUris.add(outputFile.toString())
                    recordSuccess()

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: CancellationException) {
                    deleteOutputQuietly(outputFile)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Rotate failed for $uri")
                    recordFailure()
                    deleteOutputQuietly(outputFile)
                }
            }

            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size
            )
        }
    }

    private fun createOutputFile(dirUri: Uri, sourceUri: Uri, suffix: String): Uri? {
        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)
        val originalName = docFile?.name ?: "document.pdf"
        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = originalName.substringAfterLast(".", "pdf")
        val newName = "${nameWithoutExt}${suffix}.$ext"

        val parentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, dirUri)
        return parentDir?.createFile("application/pdf", newName)?.uri
    }
}
