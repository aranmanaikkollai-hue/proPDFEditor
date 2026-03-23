package com.propdf.editor.data.repository

import android.content.Context
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
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
            val output = PdfDocument(PdfWriter(outputFile.absolutePath, bestCompression()))
            try {
                val merger = PdfMerger(output)
                inputFiles.forEach { file ->
                    if (!file.exists()) return@forEach
                    val src = PdfDocument(PdfReader(file.absolutePath))
                    try {
                        merger.merge(src, 1, src.numberOfPages)
                    } finally {
                        src.close()
                    }
                }
            } finally {
                output.close()
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
            val src = PdfDocument(PdfReader(inputFile.absolutePath))
            try {
                ranges.forEachIndexed { idx, range ->
                    val out  = File(outputDir, "${inputFile.nameWithoutExtension}_part${idx + 1}.pdf")
                    val dest = PdfDocument(PdfWriter(out.absolutePath, bestCompression()))
                    try {
                        val from = range.first.coerceIn(1, src.numberOfPages)
                        val to   = range.last.coerceIn(from, src.numberOfPages)
                        PdfMerger(dest).merge(src, from, to)
                    } finally {
                        dest.close()
                    }
                    results.add(out)
                }
            } finally {
                src.close()
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try { } finally { pdfDoc.close() }
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, props)
            )
            try { } finally { pdfDoc.close() }
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath, readerProps),
                PdfWriter(outputFile.absolutePath)
            )
            try { } finally { pdfDoc.close() }
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                val gs   = PdfExtGState().also { it.fillOpacity = opacity }
                val rad  = Math.toRadians(rotation.toDouble())
                for (i in 1..pdfDoc.numberOfPages) {
                    val page   = pdfDoc.getPage(i)
                    val ps     = page.pageSize
                    val cx     = (ps.left + ps.right) / 2f
                    val cy     = (ps.bottom + ps.top) / 2f
                    val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, pdfDoc)
                    try {
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
                    } finally {
                        canvas.release()
                    }
                }
            } finally {
                pdfDoc.close()
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                pagesToDelete.sortedDescending().forEach { num ->
                    if (num in 1..pdfDoc.numberOfPages) pdfDoc.removePage(num)
                }
            } finally {
                pdfDoc.close()
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                pages.forEach { (num, deg) ->
                    if (num in 1..pdfDoc.numberOfPages) {
                        val page = pdfDoc.getPage(num)
                        page.put(PdfName.Rotate, PdfNumber((page.rotation + deg) % 360))
                    }
                }
            } finally {
                pdfDoc.close()
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                val font  = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                val total = pdfDoc.numberOfPages
                for (i in 1..total) {
                    val page = pdfDoc.getPage(i)
                    val ps   = page.pageSize
                    var text = format.replaceFirst("%d", "$i")
                    if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                    try {
                        canvas.beginText()
                            .setFontAndSize(font, 10f)
                            .moveText(((ps.left + ps.right) / 2.0), (ps.bottom + 15.0))
                            .showText(text)
                            .endText()
                    } finally {
                        canvas.release()
                    }
                }
            } finally {
                pdfDoc.close()
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
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                for (i in 1..pdfDoc.numberOfPages) {
                    val page   = pdfDoc.getPage(i)
                    val ps     = page.pageSize
                    val cx     = (ps.left + ps.right) / 2.0
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                    try {
                        canvas.beginText().setFontAndSize(font, 10f)
                        headerText?.let { canvas.moveText(cx, (ps.top  - 20.0)).showText(it) }
                        footerText?.let { canvas.moveText(cx, (ps.bottom + 10.0)).showText(it) }
                        canvas.endText()
                    } finally {
                        canvas.release()
                    }
                }
            } finally {
                pdfDoc.close()
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
            val pdfDoc = PdfDocument(PdfWriter(outputFile.absolutePath, bestCompression()))
            val document = Document(pdfDoc)
            try {
                imageFiles.forEach { imgFile ->
                    pdfDoc.addNewPage()
                    document.add(
                        Image(ImageDataFactory.create(imgFile.absolutePath)).setAutoScale(true)
                    )
                }
            } finally {
                document.close()  // Document.close() also closes pdfDoc
            }
            outputFile
        }
    }

    // ── SAVE ANNOTATIONS INTO PDF ─────────────────────────────

    suspend fun saveAnnotationsToPdf(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDoc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, bestCompression())
            )
            try {
                for ((pageIndex, data) in pageAnnotations) {
                    val (strokes, renderScale) = data
                    if (strokes.isEmpty()) continue
                    val pdfPage = pdfDoc.getPage(pageIndex + 1)
                    val pdfH    = pdfPage.pageSize.height
                    val canvas  = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, pdfDoc)
                    try {
                        for (stroke in strokes) {
                            canvas.saveState()
                            applyStrokePaint(canvas, stroke)
                            drawAndroidPathOnPdfCanvas(canvas, stroke.path, renderScale, pdfH)
                            canvas.stroke()
                            canvas.restoreState()
                        }
                    } finally {
                        canvas.release()
                    }
                }
            } finally {
                pdfDoc.close()
            }
            outputFile
        }
    }

    // ── Stroke paint → PdfCanvas ──────────────────────────────

    private fun applyStrokePaint(canvas: PdfCanvas, stroke: AnnotationCanvasView.Stroke) {
        val paint = stroke.paint
        val r = android.graphics.Color.red(paint.color)   / 255f
        val g = android.graphics.Color.green(paint.color) / 255f
        val b = android.graphics.Color.blue(paint.color)  / 255f
        val a = paint.alpha / 255f
        canvas.setExtGState(PdfExtGState().also { it.strokeOpacity = a; it.fillOpacity = a })
        canvas.setStrokeColor(com.itextpdf.kernel.colors.DeviceRgb(r, g, b))
        canvas.setLineWidth(paint.strokeWidth / 2f)
        canvas.setLineCapStyle(1)   // PDF spec: 1 = Round cap  (replaces PdfCanvasConstants)
        canvas.setLineJoinStyle(1)  // PDF spec: 1 = Round join (replaces PdfCanvasConstants)
    }

    // ── Android Path → PDF polyline ───────────────────────────

    private fun drawAndroidPathOnPdfCanvas(
        canvas: PdfCanvas,
        path: android.graphics.Path,
        scale: Float,
        pdfPageHeight: Float
    ) {
        val measure = android.graphics.PathMeasure(path, false)
        val pos     = FloatArray(2)
        val tan     = FloatArray(2)
        val step    = 2f

        do {
            val length = measure.length
            if (length < 1f) continue
            var dist  = 0f
            var first = true
            while (dist <= length) {
                if (measure.getPosTan(dist, pos, tan)) {
                    val px = pos[0] / scale
                    val py = pdfPageHeight - (pos[1] / scale)
                    if (first) { canvas.moveTo(px.toDouble(), py.toDouble()); first = false }
                    else canvas.lineTo(px.toDouble(), py.toDouble())
                }
                dist += step
            }
            if (measure.getPosTan(length, pos, tan)) {
                canvas.lineTo((pos[0] / scale).toDouble(), (pdfPageHeight - pos[1] / scale).toDouble())
            }
        } while (measure.nextContour())
    }

    // ── Helper ────────────────────────────────────────────────

    private fun bestCompression() = WriterProperties().apply {
        setCompressionLevel(9)
        useSmartMode()
    }
}
