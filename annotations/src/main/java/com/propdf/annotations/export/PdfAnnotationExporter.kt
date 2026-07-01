package com.propdf.annotations.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.persistence.AnnotationRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Professional PDF annotation export/import with flatten and burn support.
 * Uses PDFBox for PDF manipulation and Android PdfRenderer for rasterization.
 */
class PdfAnnotationExporter(
    private val context: Context,
    private val repository: AnnotationRepository
) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    /**
     * Export annotations to JSON file.
     */
    suspend fun exportAnnotationsToJson(documentId: String, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            repository.exportToJson(documentId, outputFile)
        }

    /**
     * Import annotations from JSON file.
     */
    suspend fun importAnnotationsFromJson(documentId: String, documentPath: String, jsonFile: File): Boolean =
        withContext(Dispatchers.IO) {
            repository.importFromJson(documentId, documentPath, jsonFile).isNotEmpty()
        }

    /**
     * Flatten annotations into PDF - annotations become non-editable graphics.
     * Preserves original PDF, creates new flattened copy.
     */
    suspend fun flattenAnnotations(inputFile: File, outputFile: File, documentId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                PDDocument.load(inputFile).use { document ->
                    val annotations = repository.getUnflattenedAnnotations(documentId)

                    annotations.groupBy { it.pageIndex }.forEach { (pageIndex, pageAnnotations) ->
                        if (pageIndex < document.numberOfPages) {
                            val page = document.getPage(pageIndex)
                            val contentStream = PDPageContentStream(
                                document, page,
                                PDPageContentStream.AppendMode.APPEND, true, true
                            )

                            pageAnnotations.forEach { annotation ->
                                renderAnnotationToPdfBox(contentStream, annotation, page.mediaBox)
                            }

                            contentStream.close()
                            page.annotations.clear()
                        }
                    }

                    document.save(outputFile)
                    repository.markAnnotationsFlattened(documentId)
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    /**
     * Burn annotations permanently into PDF as rasterized bitmap.
     * Use for final output where no editing is ever needed.
     */
    suspend fun burnAnnotationsIntoPdf(
        inputFile: File,
        outputFile: File,
        documentId: String,
        dpi: Int = 300
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fd = android.os.ParcelFileDescriptor.open(
                inputFile, 
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val pdfRenderer = android.graphics.pdf.PdfRenderer(fd)

            PDDocument.load(inputFile).use { document ->
                val annotations = repository.getAnnotationsForDocument(documentId).first()

                for (i in 0 until document.numberOfPages) {
                    val page = document.getPage(i)
                    val mediaBox = page.mediaBox
                    val width = mediaBox.width
                    val height = mediaBox.height

                    val scale = dpi / 72f
                    val bitmapWidth = (width * scale).toInt().coerceAtLeast(1)
                    val bitmapHeight = (height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    // Render PDF page
                    if (i < pdfRenderer.pageCount) {
                        pdfRenderer.openPage(i).use { pdfPage ->
                            val srcRect = Rect(0, 0, pdfPage.width, pdfPage.height)
                            val dstRect = Rect(0, 0, bitmapWidth, bitmapHeight)
                            pdfPage.render(bitmap, srcRect, dstRect, null)
                        }
                    }

                    // Render annotations
                    val pageAnnotations = annotations.filter { it.pageIndex == i }
                    pageAnnotations.forEach { annotation ->
                        renderAnnotationToAndroidCanvas(canvas, annotation, scale)
                    }

                    // Create new page with burned image
                    val newPage = PDPage(PDRectangle(width, height))
                    document.addPage(newPage)

                    val image = LosslessFactory.createFromImage(document, bitmap)
                    val contentStream = PDPageContentStream(document, newPage)
                    contentStream.drawImage(image, 0f, 0f, width, height)
                    contentStream.close()

                    // Remove original page
                    document.removePage(page)
                }

                pdfRenderer.close()
                fd.close()
                document.save(outputFile)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun renderAnnotationToPdfBox(
        contentStream: PDPageContentStream,
        annotation: Annotation,
        mediaBox: PDRectangle
    ) {
        val pageHeight = mediaBox.height

        when (annotation) {
            is HighlightAnnotation -> {
                annotation.rects.forEach { rect ->
                    contentStream.setNonStrokingColor(
                        Color.red(annotation.color) / 255f,
                        Color.green(annotation.color) / 255f,
                        Color.blue(annotation.color) / 255f
                    )
                    contentStream.addRect(
                        rect.left, 
                        pageHeight - rect.bottom, 
                        rect.width(), 
                        rect.height()
                    )
                    contentStream.fill()
                }
            }
            is ShapeAnnotation -> {
                contentStream.setStrokingColor(
                    Color.red(annotation.color) / 255f,
                    Color.green(annotation.color) / 255f,
                    Color.blue(annotation.color) / 255f
                )
                contentStream.setLineWidth(annotation.strokeWidth)

                when (annotation.shapeType) {
                    ShapeAnnotation.ShapeType.RECTANGLE -> {
                        contentStream.addRect(
                            annotation.rect.left, 
                            pageHeight - annotation.rect.bottom,
                            annotation.rect.width(), 
                            annotation.rect.height()
                        )
                        contentStream.stroke()
                    }
                    ShapeAnnotation.ShapeType.CIRCLE -> {
                        val cx = annotation.rect.centerX()
                        val cy = pageHeight - annotation.rect.centerY()
                        val rx = annotation.rect.width() / 2
                        val ry = annotation.rect.height() / 2
                        drawEllipse(contentStream, cx, cy, rx, ry)
                        contentStream.stroke()
                    }
                    ShapeAnnotation.ShapeType.LINE -> {
                        contentStream.moveTo(annotation.rect.left, pageHeight - annotation.rect.top)
                        contentStream.lineTo(annotation.rect.right, pageHeight - annotation.rect.bottom)
                        contentStream.stroke()
                    }
                    else -> {}
                }

                annotation.fillColor?.let { fillColor ->
                    contentStream.setNonStrokingColor(
                        Color.red(fillColor) / 255f,
                        Color.green(fillColor) / 255f,
                        Color.blue(fillColor) / 255f
                    )
                    contentStream.fill()
                }
            }
            is TextAnnotation -> {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, annotation.fontSize)
                contentStream.setNonStrokingColor(
                    Color.red(annotation.color) / 255f,
                    Color.green(annotation.color) / 255f,
                    Color.blue(annotation.color) / 255f
                )
                contentStream.newLineAtOffset(
                    annotation.rect.left, 
                    pageHeight - annotation.rect.top
                )
                contentStream.showText(annotation.text)
                contentStream.endText()
            }
            is StrokeAnnotation -> {
                contentStream.setStrokingColor(
                    Color.red(annotation.color) / 255f,
                    Color.green(annotation.color) / 255f,
                    Color.blue(annotation.color) / 255f
                )
                contentStream.setLineWidth(annotation.strokeWidth)
                contentStream.setLineCapStyle(1) // Round cap
                contentStream.setLineJoinStyle(1) // Round join

                if (annotation.points.isNotEmpty()) {
                    val first = annotation.points[0]
                    contentStream.moveTo(first.x, pageHeight - first.y)
                    annotation.points.drop(1).forEach {
                        contentStream.lineTo(it.x, pageHeight - it.y)
                    }
                    contentStream.stroke()
                }
            }
            is StampAnnotation -> {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, annotation.fontSize)
                val stampColor = annotation.getDefaultColor()
                contentStream.setNonStrokingColor(
                    Color.red(stampColor) / 255f,
                    Color.green(stampColor) / 255f,
                    Color.blue(stampColor) / 255f
                )
                contentStream.newLineAtOffset(
                    annotation.rect.left,
                    pageHeight - annotation.rect.top
                )
                contentStream.showText(annotation.getDisplayText())
                contentStream.endText()
            }
            else -> {}
        }
    }

    private fun drawEllipse(contentStream: PDPageContentStream, cx: Float, cy: Float, rx: Float, ry: Float) {
        val kappa = 0.5522848f
        val ox = rx * kappa
        val oy = ry * kappa

        contentStream.moveTo(cx - rx, cy)
        contentStream.curveTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry)
        contentStream.curveTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy)
        contentStream.curveTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry)
        contentStream.curveTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy)
    }

    private fun renderAnnotationToAndroidCanvas(canvas: Canvas, annotation: Annotation, scale: Float) {
        val paint = Paint().apply {
            isAntiAlias = true
        }

        when (annotation) {
            is StrokeAnnotation -> {
                paint.color = annotation.color
                paint.strokeWidth = annotation.strokeWidth * scale
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND

                val path = Path()
                if (annotation.points.isNotEmpty()) {
                    val first = annotation.points[0]
                    path.moveTo(first.x * scale, first.y * scale)
                    annotation.points.drop(1).forEach {
                        path.lineTo(it.x * scale, it.y * scale)
                    }
                }
                canvas.drawPath(path, paint)
            }
            is ShapeAnnotation -> {
                paint.color = annotation.color
                paint.strokeWidth = annotation.strokeWidth * scale
                paint.style = Paint.Style.STROKE

                when (annotation.shapeType) {
                    ShapeAnnotation.ShapeType.RECTANGLE -> {
                        canvas.drawRect(
                            annotation.rect.left * scale,
                            annotation.rect.top * scale,
                            annotation.rect.right * scale,
                            annotation.rect.bottom * scale,
                            paint
                        )
                    }
                    ShapeAnnotation.ShapeType.CIRCLE -> {
                        canvas.drawOval(
                            annotation.rect.left * scale,
                            annotation.rect.top * scale,
                            annotation.rect.right * scale,
                            annotation.rect.bottom * scale,
                            paint
                        )
                    }
                    ShapeAnnotation.ShapeType.LINE -> {
                        canvas.drawLine(
                            annotation.rect.left * scale,
                            annotation.rect.top * scale,
                            annotation.rect.right * scale,
                            annotation.rect.bottom * scale,
                            paint
                        )
                    }
                    else -> {}
                }

                annotation.fillColor?.let { fillColor ->
                    paint.color = fillColor
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(
                        annotation.rect.left * scale,
                        annotation.rect.top * scale,
                        annotation.rect.right * scale,
                        annotation.rect.bottom * scale,
                        paint
                    )
                }
            }
            is TextAnnotation -> {
                paint.color = annotation.color
                paint.textSize = annotation.fontSize * scale
                paint.typeface = when {
                    annotation.isBold && annotation.isItalic -> Typeface.DEFAULT_BOLD_ITALIC
                    annotation.isBold -> Typeface.DEFAULT_BOLD
                    annotation.isItalic -> Typeface.defaultFromStyle(Typeface.ITALIC)
                    else -> Typeface.DEFAULT
                }

                val lines = annotation.text.split("\n")
                val fm = paint.fontMetrics
                val lineHeight = fm.descent - fm.ascent

                lines.forEachIndexed { index, line ->
                    val x = annotation.rect.left * scale
                    val y = annotation.rect.top * scale + (index + 1) * lineHeight - fm.descent
                    canvas.drawText(line, x, y, paint)
                }
            }
            is HighlightAnnotation -> {
                paint.color = annotation.color
                paint.alpha = (annotation.opacity * 255).toInt()
                paint.style = Paint.Style.FILL

                annotation.rects.forEach { rect ->
                    canvas.drawRect(
                        rect.left * scale,
                        rect.top * scale,
                        rect.right * scale,
                        rect.bottom * scale,
                        paint
                    )
                }
            }
            else -> {}
        }
    }
}
