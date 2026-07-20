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

                try {
                    val outputFile = createOutputFile(outputDirUri, uri, "_rotated")
                        ?: throw IllegalStateException("Cannot create output file")

                    rotatePdf(uri, outputFile, config)
                    outputUris.add(outputFile.toString())

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: Exception) {
                    Timber.e(e, "Rotate failed for $uri")
                }
            }

            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size
            )
        }
    }

    private fun rotatePdf(inputUri: Uri, outputUri: Uri, config: RotateConfig) {
        applicationContext.contentResolver.openInputStream(inputUri)?.use { input ->
            applicationContext.contentResolver.openOutputStream(outputUri)?.use { output ->
                val reader = PdfReader(input)
                val writer = PdfWriter(output)
                val pdfDoc = PdfDocument(reader, writer)

                val rotation = config.rotation % 360
                val pagesToRotate = if (config.allPages) {
                    (1..pdfDoc.numberOfPages).toList()
                } else {
                    config.pageNumbers?.filter { it in 1..pdfDoc.numberOfPages } ?: emptyList()
                }

                pagesToRotate.forEach { pageNum ->
                    val page = pdfDoc.getPage(pageNum)
                    val currentRotation = page.rotation
                    page.rotation = (currentRotation + rotation) % 360
                }

                pdfDoc.close()
            }
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
