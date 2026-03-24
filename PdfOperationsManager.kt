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
    suspend fun mergePdfs(inputFiles: List<File>, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val out = PdfDocument(PdfWriter(outputFile.absolutePath))
            try {
                val merger = PdfMerger(out)
                for (f in inputFiles) {
                    if (!f.exists()) continue
                    val src = PdfDocument(PdfReader(f.absolutePath))
                    try { merger.merge(src, 1, src.numberOfPages) }
                    finally { src.close() }
                }
            } finally { out.close() }
            outputFile
        }}

    // ── SPLIT ────────────────────────────────────────────────
    suspend fun splitPdf(inputFile: File, outputDir: File, ranges: List<IntRange>): Result<List<File>> =
        withContext(Dispatchers.IO) { runCatching {
            val result = mutableListOf<File>()
            val src = PdfDocument(PdfReader(inputFile.absolutePath))
            try {
                ranges.forEachIndexed { i, range ->
                    val out  = File(outputDir, "${inputFile.nameWithoutExtension}_part${i+1}.pdf")
                    val dest = PdfDocument(PdfWriter(out.absolutePath))
                    try {
                        PdfMerger(dest).merge(
                            src,
                            range.first.coerceIn(1, src.numberOfPages),
                            range.last.coerceIn(1, src.numberOfPages)
                        )
                    } finally { dest.close() }
                    result.add(out)
                }
            } finally { src.close() }
            result
        }}

    // ── COMPRESS ─────────────────────────────────────────────
    suspend fun compressPdf(inputFile: File, outputFile: File, quality: Int = 1): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath,
                    WriterProperties().apply { setCompressionLevel(9); useSmartMode() })
            )
            try { } finally { doc.close() }
            outputFile
        }}

    // ── ENCRYPT ──────────────────────────────────────────────
    suspend fun encryptPdf(
        inputFile: File, outputFile: File,
        userPassword: String?, ownerPassword: String,
        allowPrinting: Boolean = true, allowCopying: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        var perms = EncryptionConstants.ALLOW_SCREENREADERS
        if (allowPrinting) perms = perms or EncryptionConstants.ALLOW_PRINTING
        if (allowCopying)  perms = perms or EncryptionConstants.ALLOW_COPY
        val doc = PdfDocument(
            PdfReader(inputFile.absolutePath),
            PdfWriter(outputFile.absolutePath,
                WriterProperties().setStandardEncryption(
                    userPassword?.toByteArray(), ownerPassword.toByteArray(),
                    perms, EncryptionConstants.ENCRYPTION_AES_256
                ))
        )
        try { } finally { doc.close() }
        outputFile
    }}

    // ── REMOVE PASSWORD ──────────────────────────────────────
    suspend fun removePdfPassword(inputFile: File, outputFile: File, password: String): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(
                PdfReader(inputFile.absolutePath, ReaderProperties().setPassword(password.toByteArray())),
                PdfWriter(outputFile.absolutePath)
            )
            try { } finally { doc.close() }
            outputFile
        }}

    // ── WATERMARK ────────────────────────────────────────────
    suspend fun addTextWatermark(
        inputFile: File, outputFile: File,
        text: String, opacity: Float = 0.3f, rotation: Float = 45f
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            val gs   = PdfExtGState().also { it.fillOpacity = opacity }
            val rad  = Math.toRadians(rotation.toDouble())
            for (i in 1..doc.numberOfPages) {
                val page   = doc.getPage(i)
                val ps     = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, doc)
                try {
                    canvas.saveState().setExtGState(gs).beginText()
                        .setFontAndSize(font, 60f).setFillColor(ColorConstants.LIGHT_GRAY)
                        .setTextMatrix(
                            cos(rad).toFloat(), sin(rad).toFloat(),
                            -sin(rad).toFloat(), cos(rad).toFloat(),
                            (ps.left + ps.right) / 2f, (ps.bottom + ps.top) / 2f
                        )
                        .showText(text).endText().restoreState()
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ── DELETE PAGES ─────────────────────────────────────────
    suspend fun deletePages(inputFile: File, outputFile: File, pagesToDelete: List<Int>): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                pagesToDelete.sortedDescending().forEach { n ->
                    if (n in 1..doc.numberOfPages) doc.removePage(n)
                }
            } finally { doc.close() }
            outputFile
        }}

    // ── ROTATE ───────────────────────────────────────────────
    suspend fun rotatePages(inputFile: File, outputFile: File, pages: Map<Int, Int>): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                pages.forEach { (n, deg) ->
                    if (n in 1..doc.numberOfPages) {
                        val p = doc.getPage(n)
                        p.put(PdfName.Rotate, PdfNumber((p.rotation + deg) % 360))
                    }
                }
            } finally { doc.close() }
            outputFile
        }}

    // ── PAGE NUMBERS ─────────────────────────────────────────
    suspend fun addPageNumbers(inputFile: File, outputFile: File, format: String = "Page %d of %d"): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val font  = com.itextpdf.kernel.font.PdfFontFactory.createFont()
                val total = doc.numberOfPages
                for (i in 1..total) {
                    val page = doc.getPage(i); val ps = page.pageSize
                    var text = format.replaceFirst("%d", "$i")
                    if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        canvas.beginText().setFontAndSize(font, 10f)
                            .moveText(((ps.left + ps.right) / 2.0), (ps.bottom + 15.0))
                            .showText(text).endText()
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }}

    // ── HEADER / FOOTER ──────────────────────────────────────
    suspend fun addHeaderFooter(
        inputFile: File, outputFile: File,
        headerText: String? = null, footerText: String? = null
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            for (i in 1..doc.numberOfPages) {
                val page = doc.getPage(i); val ps = page.pageSize
                val cx   = (ps.left + ps.right) / 2.0
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    canvas.beginText().setFontAndSize(font, 10f)
                    headerText?.let { canvas.moveText(cx, (ps.top - 20.0)).showText(it) }
                    footerText?.let { canvas.moveText(cx, (ps.bottom + 10.0)).showText(it) }
                    canvas.endText()
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ── IMAGES TO PDF ────────────────────────────────────────
    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val pdfDoc   = PdfDocument(PdfWriter(outputFile.absolutePath))
            val document = Document(pdfDoc)
            try {
                imageFiles.forEach { f ->
                    pdfDoc.addNewPage()
                    document.add(Image(ImageDataFactory.create(f.absolutePath)).setAutoScale(true))
                }
            } finally { document.close() }
            outputFile
        }}

    // ── SAVE ANNOTATIONS ─────────────────────────────────────
    suspend fun saveAnnotationsToPdf(
        inputFile: File, outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            for ((idx, data) in pageAnnotations) {
                val (strokes, scale) = data
                if (strokes.isEmpty()) continue
                val pdfPage = doc.getPage(idx + 1)
                val pdfH    = pdfPage.pageSize.height
                val canvas  = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, doc)
                try {
                    for (stroke in strokes) {
                        canvas.saveState()
                        val paint = stroke.paint
                        val r = android.graphics.Color.red(paint.color)   / 255f
                        val g = android.graphics.Color.green(paint.color) / 255f
                        val b = android.graphics.Color.blue(paint.color)  / 255f
                        val a = paint.alpha / 255f
                        val gs = PdfExtGState().also { it.strokeOpacity = a; it.fillOpacity = a }
                        canvas.setExtGState(gs)
                        canvas.setStrokeColor(com.itextpdf.kernel.colors.DeviceRgb(r, g, b))
                        canvas.setLineWidth(paint.strokeWidth / 2f)
                        canvas.setLineCapStyle(1)
                        canvas.setLineJoinStyle(1)
                        val measure = android.graphics.PathMeasure(stroke.path, false)
                        val pos = FloatArray(2); val tan = FloatArray(2)
                        do {
                            val len = measure.length; if (len < 1f) continue
                            var dist = 0f; var first = true
                            while (dist <= len) {
                                if (measure.getPosTan(dist, pos, tan)) {
                                    val px = pos[0] / scale
                                    val py = pdfH - pos[1] / scale
                                    if (first) { canvas.moveTo(px.toDouble(), py.toDouble()); first = false }
                                    else canvas.lineTo(px.toDouble(), py.toDouble())
                                }
                                dist += 2f
                            }
                        } while (measure.nextContour())
                        canvas.stroke()
                        canvas.restoreState()
                    }
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}
}
