package com.propdf.annotations.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import com.propdf.annotations.persistence.AnnotationRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
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
     * Flatten annotations into the PDF: the overlay annotations are drawn into the page content as vector
     * graphics. The page's own PDF objects are NOT touched: existing links, form widgets, comments and any other
     * annotation dictionaries on the page are preserved (an earlier version cleared them).
     *
     * Refuses signed documents (a rewrite would invalidate the signature) and password-protected documents.
     * Output is written to "<name>.part", re-opened and checked, and only then moved to [outputFile]; the input
     * file is never modified; on any failure no output file is left behind and the overlay annotations are NOT
     * marked as flattened.
     *
     * Known limitation (not changed here): overlay coordinates are mapped with the MediaBox only; page /Rotate
     * and a non-zero CropBox origin are not applied (see PHASE_9 report, coordinate-system work).
     */
    suspend fun flattenAnnotations(inputFile: File, outputFile: File, documentId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            attempt {
                val annotations = repository.getUnflattenedAnnotations(documentId)
                val partial = partialFile(outputFile)
                try {
                    openForRewrite(inputFile).use { document ->
                        val pageCount = document.numberOfPages
                        val byPage = annotations.groupBy { it.pageIndex }
                        byPage.keys.firstOrNull { it < 0 || it >= pageCount }?.let { throw FlattenException.InvalidPage(it) }

                        for ((pageIndex, pageAnnotations) in byPage) {
                            val page = document.getPage(pageIndex)
                            PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true
                            ).use { contentStream ->
                                try {
                                    pageAnnotations.forEach { renderAnnotationToPdfBox(contentStream, it, page.mediaBox) }
                                } catch (e: IllegalArgumentException) {
                                    // PDFBox rejects characters the standard-14 font cannot encode.
                                    throw FlattenException.UnsupportedCharacters()
                                }
                            }
                        }
                        try {
                            document.save(partial)
                        } catch (e: java.io.IOException) {
                            throw FlattenException.WriteFailed(e)
                        }
                    }
                    verifyOutput(partial, expectedPages = null, inputFile = inputFile)
                    publish(partial, outputFile)
                    repository.markAnnotationsFlattened(documentId)
                } finally {
                    if (partial.exists()) partial.delete()
                }
            }
        }

    /**
     * Burn annotations permanently into the PDF as a rasterised bitmap per page ("flatten to image").
     * Text, links and form fields are NOT preserved in the output by design: this is the destructive option.
     * A NEW document is built page by page (the previous implementation edited the page list while iterating,
     * which skipped and re-processed pages). Refuses signed and password-protected documents; output is atomic.
     */
    suspend fun burnAnnotationsIntoPdf(
        inputFile: File,
        outputFile: File,
        documentId: String,
        dpi: Int = 300
    ): Result<Unit> = withContext(Dispatchers.IO) {
        attempt {
            val annotations = repository.getAnnotationsForDocument(documentId).first()
            val partial = partialFile(outputFile)
            var expectedPages = 0
            try {
                // Gate (and page geometry) with PDFBox; render with PdfRenderer.
                val geometry = openForRewrite(inputFile).use { src ->
                    expectedPages = src.numberOfPages
                    (0 until src.numberOfPages).map { i -> src.getPage(i).mediaBox.let { it.width to it.height } }
                }
                val fd = try {
                    android.os.ParcelFileDescriptor.open(inputFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (e: Exception) {
                    throw FlattenException.UnreadableDocument(e)
                }
                try {
                    val renderer = try {
                        android.graphics.pdf.PdfRenderer(fd)
                    } catch (e: Exception) {
                        throw FlattenException.UnreadableDocument(e)
                    }
                    try {
                        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { out ->
                            val scale = dpi / 72f
                            for (i in 0 until expectedPages) {
                                val (width, height) = geometry[i]
                                val bmpW = (width * scale).toInt().coerceIn(1, MAX_BURN_SIDE_PX)
                                val bmpH = (height * scale).toInt().coerceIn(1, MAX_BURN_SIDE_PX)
                                val effScale = bmpW / width
                                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                                try {
                                    val canvas = Canvas(bitmap)
                                    canvas.drawColor(Color.WHITE)
                                    renderer.openPage(i).use { pdfPage ->
                                        pdfPage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    }
                                    annotations.filter { it.pageIndex == i }
                                        .forEach { renderAnnotationToAndroidCanvas(canvas, it, effScale) }
                                    val newPage = PDPage(PDRectangle(width, height))
                                    out.addPage(newPage)
                                    val image = LosslessFactory.createFromImage(out, bitmap)
                                    PDPageContentStream(out, newPage).use { it.drawImage(image, 0f, 0f, width, height) }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            try {
                                out.save(partial)
                            } catch (e: java.io.IOException) {
                                throw FlattenException.WriteFailed(e)
                            }
                        }
                    } finally {
                        renderer.close()
                    }
                } finally {
                    try { fd.close() } catch (_: Exception) { }
                }
                verifyOutput(partial, expectedPages = expectedPages, inputFile = null)
                publish(partial, outputFile)
            } finally {
                if (partial.exists()) partial.delete()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Opens [file] for a full rewrite: refuses encrypted and signed documents with a typed error. */
    private fun openForRewrite(file: File): PDDocument {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val document = try {
            PDDocument.load(file, "", memory)
        } catch (e: InvalidPasswordException) {
            throw FlattenException.EncryptedDocument(e)
        } catch (e: java.io.IOException) {
            throw FlattenException.UnreadableDocument(e)
        }
        try {
            if (document.isEncrypted) throw FlattenException.EncryptedDocument()
            if (document.signatureDictionaries.isNotEmpty()) throw FlattenException.SignedDocument()
            return document
        } catch (e: Throwable) {
            try { document.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun partialFile(output: File): File {
        val parent = output.absoluteFile.parentFile ?: context.cacheDir
        return File(parent, output.name + ".part")
    }

    /**
     * Re-opens the written file. Flatten keeps the page count and must not drop existing annotation dictionaries
     * (link/widget/etc.) or form fields; burn must contain exactly [expectedPages] pages.
     */
    private fun verifyOutput(partial: File, expectedPages: Int?, inputFile: File?) {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val written = try {
            PDDocument.load(partial, "", memory)
        } catch (e: Exception) {
            throw FlattenException.OutputVerificationFailed("output cannot be reopened")
        }
        try {
            if (expectedPages != null && written.numberOfPages != expectedPages) {
                throw FlattenException.OutputVerificationFailed("page count changed")
            }
            if (inputFile != null) {
                val before = PDDocument.load(inputFile, "", memory)
                try {
                    if (before.numberOfPages != written.numberOfPages) {
                        throw FlattenException.OutputVerificationFailed("page count changed")
                    }
                    for (i in 0 until before.numberOfPages) {
                        if (before.getPage(i).annotations.size != written.getPage(i).annotations.size) {
                            throw FlattenException.OutputVerificationFailed("annotation objects changed on page ${i + 1}")
                        }
                    }
                    val formBefore = before.documentCatalog.acroForm?.fields?.size ?: 0
                    val formAfter = written.documentCatalog.acroForm?.fields?.size ?: 0
                    if (formBefore != formAfter) throw FlattenException.OutputVerificationFailed("form fields changed")
                } finally {
                    try { before.close() } catch (_: Exception) { }
                }
            }
        } finally {
            try { written.close() } catch (_: Exception) { }
        }
    }

    private fun publish(partial: File, target: File) {
        if (target.exists() && !target.delete()) throw FlattenException.WriteFailed()
        if (!partial.renameTo(target)) {
            try {
                partial.inputStream().use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
            } catch (e: java.io.IOException) {
                target.delete()
                throw FlattenException.WriteFailed(e)
            }
        }
    }

    /** runCatching that never swallows coroutine cancellation and never prints stack traces to stdout. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        android.util.Log.w("PdfAnnotationExporter", "annotation export failed: ${e.javaClass.simpleName}")
        Result.failure(e)
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
                    annotation.isBold && annotation.isItalic -> Typeface.defaultFromStyle(Typeface.BOLD_ITALIC)
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

    private companion object {
        /** Keeps a burned page bitmap under about 100 MB (ARGB_8888) even for huge page sizes at 300 dpi. */
        const val MAX_BURN_SIDE_PX = 5000
    }
}
