package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import com.propdf.editor.ui.viewer.AnnotationCanvasView

/**
 * PdfOperationsManager — Optimized PDF operations using iText 7.
 *
 * Key improvements:
 * - Every PdfDocument wrapped in .use {} — no resource leaks
 * - All operations run on Dispatchers.IO
 * - Compression level 9 applied where applicable
 * - Result.failure returned on all exceptions (no crashes)
 * - saveAnnotationsToPdf converts view-space paths → correct PDF coords
 */
@Singleton
class PdfOperationsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // ── MERGE ────────────────────────────────────────────────

    suspend fun mergePdfs(
        inputFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(PdfWriter(outputFile.absolutePath, bestCompression())).use { output ->
                val merger = PdfMerger(output)
                inputFiles.forEach { file ->
                    if (!file.exists()) return@forEach
                    PdfDocument(PdfReader(file.absolutePath)).use { src ->
                        merger.merge(src, 1, src.numberOfPages)
                    }
                }
            }
            outputFile
        }
    }

    // ── SPLIT ────────────────────────────────────────────────

    suspend fun splitPdf(
        inputFile: File,
        outputDir: File,
        ranges: List<IntRange>
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val results = mutableListOf<File>()
            PdfDocument(PdfReader(inputFile.absolutePath)).use { src ->
                ranges.forEachIndexed { idx, range ->
                    val out = File(outputDir, "${inputFile.nameWithoutExtension}_part${idx + 1}.pdf")
                    PdfDocument(PdfWriter(out.absolutePath, bestCompression())).use { dest ->
                        val from = range.first.coerceIn(1, src.numberOfPages)
                        val to   = range.last.coerceIn(from, src.numberOfPages)
                        PdfMerger(dest).merge(src, from, to)
                    }
                    results.add(out)
                }
            }
            results
        }
    }

    // ── COMPRESS ─────────────────────────────────────────────

    suspend fun compressPdf(
        inputFile: File,
        outputFile: File,
        quality: Int = 1
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val props = WriterProperties().apply {
                setFullCompressionMode(true)
                setCompressionLevel(9)   // Maximum DEFLATE compression
                useSmartMode()
            }
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, props)
            ).use { /* just open/close to rewrite with compression */ }
            outputFile
        }
    }

    // ── ENCRYPT ──────────────────────────────────────────────

    suspend fun encryptPdf(
        inputFile: File,
        outputFile: File,
        userPassword: String?,
        ownerPassword: String,
        allowPrinting: Boolean = true,
        allowCopying: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            var perms = EncryptionConstants.ALLOW_SCREENREADERS
            if (allowPrinting) perms = perms or EncryptionConstants.ALLOW_PRINTING
            if (allowCopying)  perms = perms or EncryptionConstants.ALLOW_COPY
            val props = WriterProperties().setStandardEncryption(
                userPassword?.toByteArray(),
                ownerPassword.toByteArray(),
                perms,
                EncryptionConstants.ENCRYPTION_AES_256
            )
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, props)
            ).use { }
            outputFile
        }
    }

    // ── REMOVE PASSWORD ──────────────────────────────────────

    suspend fun removePdfPassword(
        inputFile: File,
        outputFile: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val readerProps = ReaderProperties().setPassword(password.toByteArray())
            PdfDocument(
                PdfReader(inputFile.absolutePath, readerProps),
                PdfWriter(outputFile.absolutePath)
            ).use { }
            outputFile
        }
    }

    // ── WATERMARK ────────────────────────────────────────────

    suspend fun addTextWatermark(
        inputFile: File,
        outputFile: File,
        text: String,
        opacity: Float = 0.3f,
        rotation: Float = 45f
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->
                val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                val gs   = PdfExtGState().also { it.fillOpacity = opacity }
                val rad  = Math.toRadians(rotation.toDouble())

                for (i in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(i)
                    val ps   = page.pageSize
                    val cx   = (ps.left + ps.right) / 2f
                    val cy   = (ps.bottom + ps.top) / 2f

                    PdfCanvas(
                        page.newContentStreamBefore(), page.resources, pdfDoc
                    ).use { canvas ->
                        canvas.saveState()
                            .setExtGState(gs)
                            .beginText()
                            .setFontAndSize(font, 60f)
                            .setFillColor(ColorConstants.LIGHT_GRAY)
                            .setTextMatrix(
                                cos(rad).toFloat(), sin(rad).toFloat(),
                                -sin(rad).toFloat(), cos(rad).toFloat(),
                                cx, cy
                            )
                            .showText(text)
                            .endText()
                            .restoreState()
                    }
                }
            }
            outputFile
        }
    }

    // ── DELETE PAGES ─────────────────────────────────────────

    suspend fun deletePages(
        inputFile: File,
        outputFile: File,
        pagesToDelete: List<Int>
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->
                pagesToDelete.sortedDescending().forEach { num ->
                    if (num in 1..pdfDoc.numberOfPages) pdfDoc.removePage(num)
                }
            }
            outputFile
        }
    }

    // ── ROTATE PAGES ─────────────────────────────────────────

    suspend fun rotatePages(
        inputFile: File,
        outputFile: File,
        pages: Map<Int, Int>
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->
                pages.forEach { (num, deg) ->
                    if (num in 1..pdfDoc.numberOfPages) {
                        val page = pdfDoc.getPage(num)
                        page.put(PdfName.Rotate, PdfNumber((page.rotation + deg) % 360))
                    }
                }
            }
            outputFile
        }
    }

    // ── ADD PAGE NUMBERS ─────────────────────────────────────

    suspend fun addPageNumbers(
        inputFile: File,
        outputFile: File,
        format: String = "Page %d of %d"
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->
                val font  = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                val total = pdfDoc.numberOfPages
                for (i in 1..total) {
                    val page = pdfDoc.getPage(i)
                    val ps   = page.pageSize
                    val text = format
                        .replaceFirst("%d", "$i")
                        .let { if (it.contains("%d")) it.replaceFirst("%d", "$total") else it }
                    PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc).use { c ->
                        c.beginText()
                            .setFontAndSize(font, 10f)
                            .moveText(
                                ((ps.left + ps.right) / 2.0),
                                (ps.bottom + 15f).toDouble()
                            )
                            .showText(text)
                            .endText()
                    }
                }
            }
            outputFile
        }
    }

    // ── ADD HEADER / FOOTER ──────────────────────────────────

    suspend fun addHeaderFooter(
        inputFile: File,
        outputFile: File,
        headerText: String? = null,
        footerText: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->
                val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                for (i in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(i)
                    val ps   = page.pageSize
                    val cx   = ((ps.left + ps.right) / 2.0)
                    PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc).use { c ->
                        c.beginText().setFontAndSize(font, 10f)
                        headerText?.let {
                            c.moveText(cx, (ps.top - 20f).toDouble()).showText(it)
                        }
                        footerText?.let {
                            c.moveText(cx, (ps.bottom + 10f).toDouble()).showText(it)
                        }
                        c.endText()
                    }
                }
            }
            outputFile
        }
    }

    // ── IMAGES TO PDF ────────────────────────────────────────

    suspend fun imagesToPdf(
        imageFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(PdfWriter(outputFile.absolutePath, bestCompression())).use { pdfDoc ->
                Document(pdfDoc).use { doc ->
                    imageFiles.forEach { imgFile ->
                        pdfDoc.addNewPage()
                        doc.add(
                            Image(ImageDataFactory.create(imgFile.absolutePath))
                                .setAutoScale(true)
                        )
                    }
                }
            }
            outputFile
        }
    }

    // ── SAVE ANNOTATIONS INTO PDF ─────────────────────────────

    /**
     * Burn annotation strokes from view-space into PDF coordinates.
     *
     * @param pageAnnotations  Map of pageIndex (0-based) →
     *                         Pair(strokes, renderScale)
     *        renderScale = viewWidthPx / pdfPageWidthPt
     *        PDF coordinate origin is bottom-left; view origin is top-left.
     */
    suspend fun saveAnnotationsToPdf(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            ).use { pdfDoc ->

                for ((pageIndex, data) in pageAnnotations) {
                    val (strokes, renderScale) = data
                    if (strokes.isEmpty()) continue

                    // iText pages are 1-based
                    val pdfPage = pdfDoc.getPage(pageIndex + 1)
                    val pdfH    = pdfPage.pageSize.height   // PDF height in pts

                    PdfCanvas(
                        pdfPage.newContentStreamAfter(),
                        pdfPage.resources,
                        pdfDoc
                    ).use { canvas ->

                        for (stroke in strokes) {
                            canvas.saveState()
                            applyStrokePaint(canvas, stroke)

                            // Walk the Android Path segments and translate to iText
                            drawAndroidPathOnPdfCanvas(
                                canvas, stroke.path, renderScale, pdfH
                            )

                            canvas.stroke()
                            canvas.restoreState()
                        }
                    }
                }
            }
            outputFile
        }
    }

    /** Apply stroke visual properties to PdfCanvas. */
    private fun applyStrokePaint(canvas: PdfCanvas, stroke: AnnotationCanvasView.Stroke) {
        val paint = stroke.paint

        // Convert android Color → iText DeviceRgb
        val r = android.graphics.Color.red(paint.color)   / 255f
        val g = android.graphics.Color.green(paint.color) / 255f
        val b = android.graphics.Color.blue(paint.color)  / 255f
        val a = paint.alpha / 255f

        val gs = PdfExtGState().also {
            it.strokeOpacity = a
            it.fillOpacity   = a
        }
        canvas.setExtGState(gs)
        canvas.setStrokeColor(com.itextpdf.kernel.colors.DeviceRgb(r, g, b))
        canvas.setLineWidth(paint.strokeWidth / 2f)  // view px → rough pts
        canvas.setLineCapStyle(PdfCanvasConstants.LineCapStyle.ROUND)
        canvas.setLineJoinStyle(PdfCanvasConstants.LineJoinStyle.ROUND)
    }

    /**
     * Approximate Android Path → PDF path.
     *
     * Android Path stores data internally — we approximate using
     * PathMeasure to sample points along the path at regular intervals,
     * then draw as a polyline in PDF space.
     *
     * Coordinate mapping:
     *   pdfX = viewX / renderScale
     *   pdfY = pdfPageHeight - (viewY / renderScale)   ← flip Y axis
     */
    private fun drawAndroidPathOnPdfCanvas(
        canvas: PdfCanvas,
        path: Path,
        scale: Float,
        pdfPageHeight: Float
    ) {
        val measure = android.graphics.PathMeasure(path, false)
        val pos     = FloatArray(2)
        val tan     = FloatArray(2)
        val step    = 2f   // sample every 2 view-pixels

        var first     = true
        var length    = measure.length
        var distance  = 0f

        if (length < 1f) return

        while (distance <= length) {
            if (measure.getPosTan(distance, pos, tan)) {
                val pdfX = pos[0] / scale
                val pdfY = pdfPageHeight - (pos[1] / scale)

                if (first) {
                    canvas.moveTo(pdfX.toDouble(), pdfY.toDouble())
                    first = false
                } else {
                    canvas.lineTo(pdfX.toDouble(), pdfY.toDouble())
                }
            }
            distance += step
        }

        // Ensure we sample the exact end point
        if (measure.getPosTan(length, pos, tan)) {
            val pdfX = pos[0] / scale
            val pdfY = pdfPageHeight - (pos[1] / scale)
            canvas.lineTo(pdfX.toDouble(), pdfY.toDouble())
        }

        // Advance to next contour if path has multiple sub-paths
        while (measure.nextContour()) {
            length   = measure.length
            distance = 0f
            first    = true
            while (distance <= length) {
                if (measure.getPosTan(distance, pos, tan)) {
                    val pdfX = pos[0] / scale
                    val pdfY = pdfPageHeight - (pos[1] / scale)
                    if (first) { canvas.moveTo(pdfX.toDouble(), pdfY.toDouble()); first = false }
                    else canvas.lineTo(pdfX.toDouble(), pdfY.toDouble())
                }
                distance += step
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────

    /** WriterProperties with maximum DEFLATE compression. */
    private fun bestCompression() = WriterProperties().apply {
        setCompressionLevel(9)
        useSmartMode()
    }
}
