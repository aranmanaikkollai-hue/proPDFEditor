package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
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

                try {
                    val outputFile = createOutputFile(outputDirUri, uri, "_encrypted")
                        ?: throw IllegalStateException("Cannot create output file")

                    encryptPdf(uri, outputFile, config)
                    outputUris.add(outputFile.toString())

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: Exception) {
                    Timber.e(e, "Encrypt failed for $uri")
                }
            }

            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size
            )
        }
    }

    private fun encryptPdf(inputUri: Uri, outputUri: Uri, config: EncryptConfig) {
        applicationContext.contentResolver.openInputStream(inputUri)?.use { input ->
            applicationContext.contentResolver.openOutputStream(outputUri)?.use { output ->
                val reader = PdfReader(input)
                
                val permissions = calculatePermissions(config)
                val encryptionAlgorithm = when (config.encryptionLevel) {
                    "AES_128" -> EncryptionConstants.ENCRYPTION_AES_128
                    "AES_256" -> EncryptionConstants.ENCRYPTION_AES_256
                    else -> EncryptionConstants.STANDARD_ENCRYPTION_128
                }

                val writerProperties = WriterProperties().apply {
                    setStandardEncryption(
                        config.password.toByteArray(Charsets.UTF_8),
                        config.password.toByteArray(Charsets.UTF_8),
                        permissions,
                        encryptionAlgorithm
                    )
                }

                val writer = PdfWriter(output, writerProperties)
                val pdfDoc = PdfDocument(reader, writer)
                pdfDoc.close()
            }
        }
    }

    private fun calculatePermissions(config: EncryptConfig): Int {
        var permissions = 0
        if (config.allowPrinting) permissions = permissions or EncryptionConstants.ALLOW_PRINTING
        if (config.allowCopying) permissions = permissions or EncryptionConstants.ALLOW_COPY
        if (config.allowModifying) permissions = permissions or EncryptionConstants.ALLOW_MODIFY_CONTENTS
        return permissions
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
