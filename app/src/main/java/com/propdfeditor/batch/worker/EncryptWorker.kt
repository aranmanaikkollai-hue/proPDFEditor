package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.EncryptParams
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class EncryptWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class EncryptConfig(
        val password: String,
        val allowPrinting: Boolean = true,
        val allowCopying: Boolean = true,
        val allowModifying: Boolean = false,
        val encryptionLevel: String = "AES_256", // AES_128, AES_256, STANDARD
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, EncryptConfig::class.java)
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
                    outputFile = createOutputFile(outputDirUri, uri, "_encrypted")
                        ?: throw IllegalStateException("Cannot create output file")

                    pdfProcessor.encryptPdf(
                        applicationContext,
                        uri,
                        outputFile,
                        EncryptParams(
                            password = config.password,
                            allowPrinting = config.allowPrinting,
                            allowCopying = config.allowCopying,
                            allowModifying = config.allowModifying,
                            level = config.encryptionLevel
                        )
                    )
                    outputUris.add(outputFile.toString())
                    recordSuccess()

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: CancellationException) {
                    deleteOutputQuietly(outputFile)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Encrypt failed for $uri")
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
