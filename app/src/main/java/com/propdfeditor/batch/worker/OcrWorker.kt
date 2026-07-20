package com.propdfeditor.batch.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class OcrWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class OcrConfig(
        val language: String = "en",
        val outputFormat: String = "PDF", // PDF, TXT
        val outputDirUri: String
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, OcrConfig::class.java)
        val inputUris = job.inputUris
        val outputDirUri = Uri.parse(config.outputDirUri)
        val outputUris = mutableListOf<String>()

        return withContext(Dispatchers.IO) {
            try {
                inputUris.forEachIndexed { index, uri ->
                    if (isStopped) {
                        isCancelled = true
                        recognizer.close()
                        return@withContext workDataOf("cancelled" to true)
                    }

                    try {
                        val outputFile = createOutputFile(outputDirUri, uri, "_ocr")
                            ?: throw IllegalStateException("Cannot create output file")

                        processOcr(uri, outputFile, config)
                        outputUris.add(outputFile.toString())

                        val progress = ((index + 1) * 100) / inputUris.size
                        updateProgress(progress, index + 1, inputUris.size)
                    } catch (e: Exception) {
                        Timber.e(e, "OCR failed for $uri")
                    }
                }

                workDataOf(
                    "output_uris" to Gson().toJson(outputUris),
                    "count" to outputUris.size
                )
            } finally {
                recognizer.close()
            }
        }
    }

    private suspend fun processOcr(inputUri: Uri, outputUri: Uri, config: OcrConfig) {
        val context = applicationContext
        val tempFile = File(context.cacheDir, "ocr_temp_${System.currentTimeMillis()}.pdf")
        
        try {
            // Copy input to temp file
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val parcelFileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val stringBuilder = StringBuilder()

            for (pageIndex in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(pageIndex)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                
                stringBuilder.appendLine("--- Page ${pageIndex + 1} ---")
                stringBuilder.appendLine(result.text)
                stringBuilder.appendLine()

                page.close()
                bitmap.recycle()
            }

            pdfRenderer.close()
            parcelFileDescriptor.close()

            // Write output
            when (config.outputFormat) {
                "TXT" -> writeTextOutput(outputUri, stringBuilder.toString())
                else -> writePdfOutput(outputUri, stringBuilder.toString())
            }

        } finally {
            tempFile.delete()
        }
    }

    private fun writeTextOutput(outputUri: Uri, text: String) {
        applicationContext.contentResolver.openOutputStream(outputUri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writePdfOutput(outputUri: Uri, text: String) {
        applicationContext.contentResolver.openOutputStream(outputUri)?.use { output ->
            val writer = PdfWriter(output)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc)

            text.split("\n").forEach { line ->
                document.add(Paragraph(line))
            }

            document.close()
        }
    }

    private fun createOutputFile(dirUri: Uri, sourceUri: Uri, suffix: String): Uri? {
        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)
        val originalName = docFile?.name ?: "document.pdf"
        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = if (config.outputFormat == "TXT") "txt" else "pdf"
        val newName = "${nameWithoutExt}${suffix}.$ext"

        val mimeType = if (config.outputFormat == "TXT") "text/plain" else "application/pdf"
        val parentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, dirUri)
        return parentDir?.createFile(mimeType, newName)?.uri
    }
}
