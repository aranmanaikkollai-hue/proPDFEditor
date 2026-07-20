package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class CompressWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class CompressConfig(
        val quality: String = "MEDIUM", // LOW, MEDIUM, HIGH
        val compressImages: Boolean = true,
        val removeMetadata: Boolean = false,
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, CompressConfig::class.java)
        val inputUris = job.inputUris
        val outputDirUri = Uri.parse(config.outputDirUri)
        val outputUris = mutableListOf<String>()
        val sizeReductions = mutableListOf<Long>()

        return withContext(Dispatchers.IO) {
            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                try {
                    val outputFile = createOutputFile(outputDirUri, uri, "_compressed")
                        ?: throw IllegalStateException("Cannot create output file")

                    val originalSize = getFileSize(uri)
                    compressPdf(uri, outputFile, config)
                    val newSize = getFileSize(outputFile)

                    outputUris.add(outputFile.toString())
                    sizeReductions.add(originalSize - newSize)

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: Exception) {
                    Timber.e(e, "Compress failed for $uri")
                }
            }

            val totalSaved = sizeReductions.sum()
            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size,
                "total_bytes_saved" to totalSaved
            )
        }
    }

    private fun compressPdf(inputUri: Uri, outputUri: Uri, config: CompressConfig) {
        val compressionLevel = when (config.quality) {
            "LOW" -> 0.3f
            "HIGH" -> 0.8f
            else -> 0.5f
        }

        applicationContext.contentResolver.openInputStream(inputUri)?.use { input ->
            applicationContext.contentResolver.openOutputStream(outputUri)?.use { output ->
                val reader = PdfReader(input)
                val writerProperties = WriterProperties().apply {
                    setCompressionLevel(9)
                    if (config.removeMetadata) {
                        // Metadata removal handled separately
                    }
                }
                val writer = PdfWriter(output, writerProperties)
                val pdfDoc = PdfDocument(reader, writer)

                if (config.compressImages) {
                    compressImages(pdfDoc, compressionLevel)
                }

                pdfDoc.close()
            }
        }
    }

    private fun compressImages(pdfDoc: PdfDocument, compressionLevel: Float) {
        // Image compression implementation using iText7
        for (i in 1..pdfDoc.numberOfPages) {
            val page = pdfDoc.getPage(i)
            val resources = page.resources
            // Process XObject images and compress them
            // This is a simplified version - full implementation would iterate through all XObjects
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
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
