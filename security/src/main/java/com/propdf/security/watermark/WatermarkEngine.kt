package com.propdf.security.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** PDFBox implementation of the watermark operations. Inputs are copied from SAF first. */
@Singleton
class WatermarkEngine @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun addTextWatermark(sourceUri: Uri, outputFile: File, text: String, options: WatermarkOptions = WatermarkOptions()): Result<File> =
        watermark(sourceUri, outputFile) { document, page ->
            val box = page.mediaBox
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.setNonStrokingColor(options.color)
                stream.setFont(PDType1Font.HELVETICA, options.fontSize)
                stream.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = options.opacity })
                stream.saveGraphicsState(); stream.transform(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(options.rotation.toDouble()).toFloat(), box.width / 2, box.height / 2))
                stream.beginText(); stream.newLineAtOffset(box.width / 2 - text.length * options.fontSize / 4, box.height / 2); stream.showText(text); stream.endText(); stream.restoreGraphicsState()
            }
        }

    suspend fun addImageWatermark(sourceUri: Uri, outputFile: File, imageBitmap: Bitmap, options: WatermarkOptions = WatermarkOptions()): Result<File> =
        watermark(sourceUri, outputFile) { document, page ->
            val image = JPEGFactory.createFromImage(document, imageBitmap, 0.9f); val box = page.mediaBox
            val width = image.width * options.scale; val height = image.height * options.scale
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = options.opacity })
                stream.drawImage(image, (box.width - width) / 2, (box.height - height) / 2, width, height)
            }
        }

    suspend fun addTiledWatermark(sourceUri: Uri, outputFile: File, text: String, options: WatermarkOptions = WatermarkOptions(), tileSpacingX: Float = 200f, tileSpacingY: Float = 150f): Result<File> =
        watermark(sourceUri, outputFile) { document, page ->
            val box = page.mediaBox
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.setNonStrokingColor(options.color); stream.setFont(PDType1Font.HELVETICA, options.fontSize)
                stream.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = options.opacity })
                var y = 50f; while (y < box.height - 50f) { var x = 50f; while (x < box.width - 50f) { stream.beginText(); stream.newLineAtOffset(x, y); stream.showText(text); stream.endText(); x += tileSpacingX }; y += tileSpacingY }
            }
        }

    private suspend fun watermark(sourceUri: Uri, outputFile: File, draw: (PDDocument, com.tom_roush.pdfbox.pdmodel.PDPage) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val input = File.createTempFile("watermark", ".pdf", context.cacheDir)
            try { context.contentResolver.openInputStream(sourceUri)?.use { source -> input.outputStream().use { target -> source.copyTo(target) } } ?: error("Cannot open input PDF")
                PDDocument.load(input).use { document -> document.pages.forEach { draw(document, it) }; document.save(outputFile) }; outputFile
            } finally { input.delete() }
        }
    }
}

data class WatermarkOptions(val fontSize: Float = 48f, val opacity: Float = 0.3f, val rotation: Float = 45f, val scale: Float = 0.5f, val color: Int = Color.GRAY)
