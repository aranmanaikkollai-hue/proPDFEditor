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

class DecryptWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class DecryptConfig(
        val password: String,
        val outputDirUri: String
    ) {
        /** Secrets stay out of any log line, exception message or crash report that prints this object. */
        override fun toString(): String = "DecryptConfig(password=<redacted>)"
    }

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        // The password is decrypted here, in memory only, and never written back anywhere.
        val config = Gson().fromJson(resolvedConfigJson(setOf("password")), DecryptConfig::class.java)
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
                    outputFile = createOutputFile(outputDirUri, uri, "_decrypted")
                        ?: throw IllegalStateException("Cannot create output file")

                    pdfProcessor.decryptPdf(applicationContext, uri, outputFile, config.password)
                    outputUris.add(outputFile.toString())
                    recordSuccess()

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: CancellationException) {
                    deleteOutputQuietly(outputFile)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Decrypt failed for $uri")
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
