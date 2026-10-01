package com.propdfeditor.batch.worker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.propdfeditor.batch.util.WatermarkParams
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class WatermarkWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class WatermarkConfig(
        val text: String = "Watermark",
        val fontSize: Float = 48f,
        val opacity: Float = 0.3f,
        val colorHex: String = "#808080",
        val rotation: Float = 45f,
        val position: String = "CENTER", // CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        val imageUri: String? = null,
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, WatermarkConfig::class.java)
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
                    outputFile = createOutputFile(outputDirUri, uri, "_watermarked")
                        ?: throw IllegalStateException("Cannot create output file")

                    val colorArgb = Color.parseColor(config.colorHex)
                    pdfProcessor.watermarkPdf(
                        applicationContext,
                        uri,
                        outputFile,
                        WatermarkParams(
                            text = config.text,
                            fontSize = config.fontSize,
                            opacity = config.opacity,
                            colorArgb = colorArgb,
                            rotationDegrees = config.rotation,
                            position = config.position,
                            imageUri = config.imageUri?.let { Uri.parse(it) }
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
                    Timber.e(e, "Watermark failed for $uri")
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
