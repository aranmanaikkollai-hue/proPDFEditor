package com.propdfeditor.batch.worker

import android.content.Context
import android.graphics.Bitmap
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
import com.google.gson.Gson
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.layout.Canvas as ItextCanvas
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

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

                try {
                    val outputFile = createOutputFile(outputDirUri, uri, "_watermarked")
                        ?: throw IllegalStateException("Cannot create output file")

                    applyWatermark(uri, outputFile, config)
                    outputUris.add(outputFile.toString())

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: Exception) {
                    Timber.e(e, "Watermark failed for $uri")
                    // Continue with next file
                }
            }

            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size
            )
        }
    }

    private fun applyWatermark(inputUri: Uri, outputUri: Uri, config: WatermarkConfig) {
        val context = applicationContext
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            context.contentResolver.openOutputStream(outputUri)?.use { output ->
                val pdfReader = PdfReader(input)
                val pdfWriter = PdfWriter(output)
                val pdfDoc = PdfDocument(pdfReader, pdfWriter)

                val color = parseColor(config.colorHex)
                val opacity = config.opacity.coerceIn(0f, 1f)

                for (i in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(i)
                    val pageSize = page.pageSize
                    val canvas = PdfCanvas(page)

                    if (config.imageUri != null) {
                        applyImageWatermark(canvas, pageSize, config)
                    } else {
                        applyTextWatermark(canvas, pageSize, config, color, opacity)
                    }
                }

                pdfDoc.close()
            }
        }
    }

    private fun applyTextWatermark(
        canvas: PdfCanvas,
        pageSize: Rectangle,
        config: WatermarkConfig,
        color: DeviceRgb,
        opacity: Float
    ) {
        val gState = PdfExtGState().apply {
            setFillOpacity(opacity)
        }
        canvas.saveState()
        canvas.setExtGState(gState)

        val x = when (config.position) {
            "TOP_LEFT" -> pageSize.left + 50
            "TOP_RIGHT" -> pageSize.right - 50
            "BOTTOM_LEFT" -> pageSize.left + 50
            "BOTTOM_RIGHT" -> pageSize.right - 50
            else -> (pageSize.left + pageSize.right) / 2
        }

        val y = when (config.position) {
            "TOP_LEFT", "TOP_RIGHT" -> pageSize.top - 50
            "BOTTOM_LEFT", "BOTTOM_RIGHT" -> pageSize.bottom + 50
            else -> (pageSize.top + pageSize.bottom) / 2
        }

        val paragraph = Paragraph(config.text)
            .setFontSize(config.fontSize)
            .setFontColor(color)
            .setTextAlignment(TextAlignment.CENTER)

        val itextCanvas = ItextCanvas(canvas, pageSize)
        itextCanvas.showTextAligned(
            paragraph,
            x,
            y,
            TextAlignment.CENTER,
            VerticalAlignment.MIDDLE,
            config.rotation
        )

        canvas.restoreState()
    }

    private fun applyImageWatermark(canvas: PdfCanvas, pageSize: Rectangle, config: WatermarkConfig) {
        // Implementation for image watermark
        val imageUri = Uri.parse(config.imageUri ?: return)
        val context = applicationContext
        
        context.contentResolver.openInputStream(imageUri)?.use { stream ->
            val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            val imageData = com.itextpdf.io.image.ImageDataFactory.create(byteArrayOutputStream.toByteArray())
            
            val image = com.itextpdf.layout.element.Image(imageData)
            image.setOpacity(config.opacity.toDouble())
            image.setFixedPosition(
                (pageSize.width - 200) / 2,
                (pageSize.height - 200) / 2,
                200f
            )
            
            val itextCanvas = ItextCanvas(canvas, pageSize)
            itextCanvas.add(image)
        }
    }

    private fun parseColor(hex: String): DeviceRgb {
        val color = Color.parseColor(hex)
        return DeviceRgb(Color.red(color), Color.green(color), Color.blue(color))
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
