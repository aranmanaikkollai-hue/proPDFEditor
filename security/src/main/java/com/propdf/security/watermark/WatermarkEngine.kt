package com.propdf.security.watermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
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
import java.io.File

/**
 * Professional PDF watermark engine.
 * Supports: text watermarks, image watermarks, opacity, rotation, tiling.
 */
class WatermarkEngine(private val context: Context) {

    /**
     * Add a text watermark to all pages.
     */
    suspend fun addTextWatermark(
        sourceUri: Uri,
        outputFile: File,
        text: String,
        options: WatermarkOptions = WatermarkOptions()
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            val reader = PdfReader(sourceFile)
            val writer = PdfWriter(outputFile.absolutePath)
            val doc = PdfDocument(reader, writer)

            val fontSize = options.fontSize
            val opacity = options.opacity
            val rotation = options.rotation

            for (i in 1..doc.numberOfPages) {
                val page = doc.getPage(i)
                val canvas = PdfCanvas(page)
                val pageSize = page.pageSize

                canvas.saveState()
                val gs1 = PdfExtGState().apply { setFillOpacity(opacity) }
                canvas.setExtGState(gs1)

                val canvasLayout = ItextCanvas(canvas, pageSize)
                canvasLayout.showTextAligned(
                    Paragraph(text).setFontSize(fontSize).setFontColor(com.itextpdf.kernel.colors.ColorConstants.GRAY),
                    pageSize.width / 2,
                    pageSize.height / 2,
                    i,
                    TextAlignment.CENTER,
                    VerticalAlignment.MIDDLE,
                    rotation
                )
                canvasLayout.close()
                canvas.restoreState()
            }

            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add an image watermark to all pages.
     */
    suspend fun addImageWatermark(
        sourceUri: Uri,
        outputFile: File,
        imageBitmap: Bitmap,
        options: WatermarkOptions = WatermarkOptions()
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            val reader = PdfReader(sourceFile)
            val writer = PdfWriter(outputFile.absolutePath)
            val doc = PdfDocument(reader, writer)

            // Convert bitmap to iText Image
            val byteArrayOutput = java.io.ByteArrayOutputStream()
            imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutput)
            val imageData = com.itextpdf.io.image.ImageDataFactory.create(byteArrayOutput.toByteArray())
            val image = com.itextpdf.layout.element.Image(imageData)

            val opacity = options.opacity
            val scale = options.scale

            for (i in 1..doc.numberOfPages) {
                val page = doc.getPage(i)
                val canvas = PdfCanvas(page)
                val pageSize = page.pageSize

                canvas.saveState()
                val gs1 = PdfExtGState().apply { setFillOpacity(opacity) }
                canvas.setExtGState(gs1)

                val imgWidth = imageBitmap.width * scale
                val imgHeight = imageBitmap.height * scale
                val x = (pageSize.width - imgWidth) / 2
                val y = (pageSize.height - imgHeight) / 2

                canvas.addImageWithTransformationMatrix(
                    imageData,
                    imgWidth, 0f, 0f, imgHeight,
                    x.toFloat(), y.toFloat()
                )

                canvas.restoreState()
            }

            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add tiled watermark (repeated across page).
     */
    suspend fun addTiledWatermark(
        sourceUri: Uri,
        outputFile: File,
        text: String,
        options: WatermarkOptions = WatermarkOptions(),
        tileSpacingX: Float = 200f,
        tileSpacingY: Float = 150f
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            val reader = PdfReader(sourceFile)
            val writer = PdfWriter(outputFile.absolutePath)
            val doc = PdfDocument(reader, writer)

            val fontSize = options.fontSize
            val opacity = options.opacity
            val rotation = options.rotation

            for (i in 1..doc.numberOfPages) {
                val page = doc.getPage(i)
                val canvas = PdfCanvas(page)
                val pageSize = page.pageSize

                canvas.saveState()
                val gs1 = PdfExtGState().apply { setFillOpacity(opacity) }
                canvas.setExtGState(gs1)

                val canvasLayout = ItextCanvas(canvas, pageSize)

                var y = pageSize.height - 50
                while (y > 50) {
                    var x = 50f
                    while (x < pageSize.width - 50) {
                        canvasLayout.showTextAligned(
                            Paragraph(text).setFontSize(fontSize).setFontColor(com.itextpdf.kernel.colors.ColorConstants.GRAY),
                            x.toDouble(), y.toDouble(), i,
                            TextAlignment.CENTER,
                            VerticalAlignment.MIDDLE,
                            rotation
                        )
                        x += tileSpacingX
                    }
                    y -= tileSpacingY
                }

                canvasLayout.close()
                canvas.restoreState()
            }

            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class WatermarkOptions(
    val fontSize: Float = 48f,
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val scale: Float = 0.5f,
    val color: Int = Color.GRAY
)
