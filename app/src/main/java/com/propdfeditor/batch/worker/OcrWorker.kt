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
import com.propdfeditor.batch.util.OcrLine
import com.propdfeditor.batch.util.OcrPage
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
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

    private companion object {
        const val MAX_OCR_DIMENSION = 3000f
        const val MAX_OCR_SCALE = 3f
    }

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

                    var outputFile: Uri? = null
                    try {
                        outputFile = createOutputFile(outputDirUri, uri, "_ocr", config.outputFormat)
                            ?: throw IllegalStateException("Cannot create output file")

                        processOcr(uri, outputFile, config)
                        outputUris.add(outputFile.toString())
                        recordSuccess()

                        val progress = ((index + 1) * 100) / inputUris.size
                        updateProgress(progress, index + 1, inputUris.size)
                    } catch (e: CancellationException) {
                        deleteOutputQuietly(outputFile)
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "OCR failed for $uri")
                        recordFailure()
                        deleteOutputQuietly(outputFile)
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
            val input = context.contentResolver.openInputStream(inputUri)
                ?: throw java.io.IOException("Cannot open input file")
            input.use { source -> tempFile.outputStream().use { sink -> source.copyTo(sink) } }

            val stringBuilder = StringBuilder()
            val ocrPages = mutableListOf<OcrPage>()

            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val pdfRenderer = PdfRenderer(descriptor)
                try {
                    for (pageIndex in 0 until pdfRenderer.pageCount) {
                        if (isStopped) throw CancellationException("OCR cancelled")
                        val page = pdfRenderer.openPage(pageIndex)
                        try {
                            // Render above 72 dpi for OCR accuracy; text positions are scaled back
                            // to page space in PdfProcessor using the bitmap size.
                            val scale = (MAX_OCR_DIMENSION / maxOf(page.width, page.height).toFloat())
                                .coerceIn(1f, MAX_OCR_SCALE)
                            val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
                            val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

                                stringBuilder.appendLine("--- Page ${pageIndex + 1} ---")
                                stringBuilder.appendLine(result.text)
                                stringBuilder.appendLine()

                                if (config.outputFormat != "TXT") {
                                    val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                        val box = line.boundingBox ?: return@mapNotNull null
                                        OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                                    }
                                    ocrPages.add(OcrPage(bitmapWidth, bitmapHeight, lines))
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    pdfRenderer.close()
                }
            }

            // Write output
            when (config.outputFormat) {
                "TXT" -> writeTextOutput(outputUri, stringBuilder.toString())
                // Original pages are kept; OCR text is added as an invisible layer.
                else -> pdfProcessor.writeSearchablePdf(context, tempFile, outputUri, ocrPages)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun writeTextOutput(outputUri: Uri, text: String) {
        val output = applicationContext.contentResolver.openOutputStream(outputUri, "wt")
            ?: throw java.io.IOException("Cannot write to output file")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun createOutputFile(dirUri: Uri, sourceUri: Uri, suffix: String, outputFormat: String): Uri? {
        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)
        val originalName = docFile?.name ?: "document.pdf"
        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = if (outputFormat == "TXT") "txt" else "pdf"
        val newName = "${nameWithoutExt}${suffix}.$ext"

        val mimeType = if (outputFormat == "TXT") "text/plain" else "application/pdf"
        val parentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, dirUri)
        return parentDir?.createFile(mimeType, newName)?.uri
    }
}
