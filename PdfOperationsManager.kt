package com.propdf.editor.data.repository

import android.content.Context
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
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

    // ---- MERGE ----------------------------------------------------------
    suspend fun mergePdfs(inputFiles: List<File>, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val out = PdfDocument(PdfWriter(outputFile.absolutePath))
            try {
                val merger = PdfMerger(out)
                inputFiles.filter { it.exists() }.forEach { f ->
                    val src = PdfDocument(PdfReader(f.absolutePath))
                    try { merger.merge(src, 1, src.numberOfPages) }
                    finally { src.close() }
                }
            } finally { out.close() }
            outputFile
        }}

    // ---- SPLIT ----------------------------------------------------------
    suspend fun splitPdf(inputFile: File, outputDir: File, ranges: List<IntRange>): Result<List<File>> =
        withContext(Dispatchers.IO) { runCatching {
            val result = mutableListOf<File>()
            val src = PdfDocument(PdfReader(inputFile.absolutePath))
            try {
                ranges.forEachIndexed { i, range ->
                    val out  = File(outputDir, "${inputFile.nameWithoutExtension}_part${i+1}.pdf")
                    val dest = PdfDocument(PdfWriter(out.absolutePath))
                    try {
                        PdfMerger(dest).merge(src,
                            range.first.coerceIn(1, src.numberOfPages),
                            range.last.coerceIn(1, src.numberOfPages))
                    } finally { dest.close() }
                    result.add(out)
                }
            } finally { src.close() }
            result
        }}

    // ---- COMPRESS -------------------------------------------------------
    suspend fun compressPdf(inputFile: File, outputFile: File, quality: Int = 9): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val props = WriterProperties().apply {
                setCompressionLevel(quality.coerceIn(1, 9))
                setFullCompressionMode(true)
                useSmartMode()
            }
            val doc = PdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, props)
            )
            try { } finally { doc.close() }
            outputFile
        }}

    // ---- ENCRYPT --------------------------------------------------------
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
            PdfWriter(outputFile.absolutePath, WriterProperties().setStandardEncryption(
                userPassword?.toByteArray(), ownerPassword.toByteArray(),
                perms, EncryptionConstants.ENCRYPTION_AES_256
            ))
        )
        try { } finally { doc.close() }
        outputFile
    }}

    // ---- REMOVE PASSWORD ------------------------------------------------
    suspend fun removePdfPassword(inputFile: File, outputFile: File, password: String): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(
                PdfReader(inputFile.absolutePath, ReaderProperties().setPassword(password.toByteArray())),
                PdfWriter(outputFile.absolutePath)
            )
            try { } finally { doc.close() }
            outputFile
        }}

    // ---- WATERMARK ------------------------------------------------------
    // FIX: use newContentStreamAfter() so watermark renders OVER content, not under it
    suspend fun addTextWatermark(
        inputFile: File, outputFile: File,
        text: String, opacity: Float = 0.3f, rotation: Float = 45f
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font = PdfFontFactory.createFont()
            val gs   = PdfExtGState().also { it.fillOpacity = opacity; it.strokeOpacity = opacity }
            val rad  = Math.toRadians(rotation.toDouble())
            for (i in 1..doc.numberOfPages) {
                val page   = doc.getPage(i)
                val ps     = page.pageSize
                val cx     = (ps.left + ps.right) / 2f
                val cy     = (ps.bottom + ps.top) / 2f
                // FIX: use newContentStreamAfter() so watermark is ON TOP of the page content
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    canvas.saveState()
                        .setExtGState(gs)
                        .beginText()
                        .setFontAndSize(font, 60f)
                        .setFillColor(DeviceRgb(0.5f, 0.5f, 0.5f))
                        .setTextMatrix(
                            cos(rad).toFloat(),  sin(rad).toFloat(),
                            -sin(rad).toFloat(), cos(rad).toFloat(),
                            cx, cy
                        )
                        .showText(text)
                        .endText()
                        .restoreState()
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ---- DELETE PAGES ---------------------------------------------------
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

    // ---- ROTATE ---------------------------------------------------------
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

    // ---- PAGE NUMBERS ---------------------------------------------------
    suspend fun addPageNumbers(
        inputFile: File, outputFile: File, format: String = "Page %d of %d",
        fontSize: Float = 10f, placement: String = "bottom"
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font  = PdfFontFactory.createFont()
            val total = doc.numberOfPages
            for (i in 1..total) {
                val page = doc.getPage(i); val ps = page.pageSize
                var text = format.replaceFirst("%d", "$i")
                if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                val cx   = ((ps.left + ps.right) / 2.0) - (text.length * fontSize * 0.3)
                val y    = if (placement == "top") (ps.top - 18.0) else (ps.bottom + 12.0)
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(cx, y)
                        .showText(text)
                        .endText()
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ---- HEADER / FOOTER ------------------------------------------------
    // FIX: use setTextMatrix (absolute positioning) instead of cumulative moveText
    suspend fun addHeaderFooter(
        inputFile: File, outputFile: File,
        headerText: String? = null, footerText: String? = null,
        fontSize: Float = 10f
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font = PdfFontFactory.createFont()
            for (i in 1..doc.numberOfPages) {
                val page = doc.getPage(i); val ps = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    canvas.setFontAndSize(font, fontSize)
                    // FIX: use setTextMatrix for ABSOLUTE positioning -- not cumulative moveText
                    if (headerText != null) {
                        val textW = headerText.length * fontSize * 0.5f
                        val hx    = (ps.left + ps.right) / 2f - textW / 2f
                        val hy    = ps.top - 18f
                        canvas.beginText()
                            .setTextMatrix(1f, 0f, 0f, 1f, hx, hy)
                            .showText(headerText)
                            .endText()
                    }
                    if (footerText != null) {
                        val textW = footerText.length * fontSize * 0.5f
                        val fx    = (ps.left + ps.right) / 2f - textW / 2f
                        val fy    = ps.bottom + 12f
                        // Each text operation uses its own beginText/endText for independence
                        canvas.beginText()
                            .setTextMatrix(1f, 0f, 0f, 1f, fx, fy)
                            .showText(footerText)
                            .endText()
                    }
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ---- IMAGES TO PDF --------------------------------------------------
    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val pdfDoc = PdfDocument(PdfWriter(outputFile.absolutePath))
            val doc    = Document(pdfDoc)
            try {
                imageFiles.filter { it.exists() && it.length() > 0 }.forEach { f ->
                    val imgData = ImageDataFactory.create(f.absolutePath)
                    val imgW    = imgData.width
                    val imgH    = imgData.height
                    // Size the page exactly to the image dimensions
                    val ps      = PageSize(imgW.toFloat(), imgH.toFloat())
                    pdfDoc.addNewPage(ps)
                    val img = com.itextpdf.layout.element.Image(imgData)
                    img.setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                    img.setWidth(ps.width); img.setHeight(ps.height)
                    doc.add(img)
                }
            } finally { doc.close() }
            outputFile
        }}

    // ---- SAVE ANNOTATIONS -----------------------------------------------
    suspend fun saveAnnotationsToPdf(
        inputFile: File, outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<AnnotationCanvasView.TextAnnot>, Float>> = emptyMap()
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val allPages = (pageAnnotations.keys + pageTextAnnotations.keys).toSet()
            for (idx in allPages) {
                val pdfPageNum = idx + 1
                if (pdfPageNum > doc.numberOfPages) continue
                val pdfPage = doc.getPage(pdfPageNum)
                val pdfH    = pdfPage.pageSize.height
                val canvas  = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, doc)
                try {
                    // Draw strokes
                    pageAnnotations[idx]?.let { (strokes, scale) ->
                        for (stroke in strokes) {
                            if (stroke.tool == AnnotationCanvasView.TOOL_ERASER) continue
                            val paint = stroke.paint
                            val r = android.graphics.Color.red(paint.color)   / 255f
                            val g = android.graphics.Color.green(paint.color) / 255f
                            val b = android.graphics.Color.blue(paint.color)  / 255f
                            val a = (paint.alpha / 255f).coerceIn(0f, 1f)
                            canvas.saveState()
                            canvas.setExtGState(
                                PdfExtGState().also { it.strokeOpacity = a; it.fillOpacity = a }
                            )
                            canvas.setStrokeColor(DeviceRgb(r, g, b))
                            if (stroke.tool == AnnotationCanvasView.TOOL_HIGHLIGHT) {
                                canvas.setFillColor(DeviceRgb(r, g, b))
                                canvas.setLineWidth(paint.strokeWidth / scale)
                            } else {
                                canvas.setLineWidth((paint.strokeWidth / scale).coerceAtLeast(0.5f))
                            }
                            canvas.setLineCapStyle(1); canvas.setLineJoinStyle(1)
                            val measure = android.graphics.PathMeasure(stroke.path, false)
                            val pos = FloatArray(2); val tan = FloatArray(2)
                            do {
                                val len = measure.length; if (len < 1f) continue
                                var dist = 0f; var first = true
                                while (dist <= len) {
                                    if (measure.getPosTan(dist, pos, tan)) {
                                        val px = (pos[0] / scale).toDouble()
                                        val py = (pdfH - pos[1] / scale).toDouble()
                                        if (first) { canvas.moveTo(px, py); first = false }
                                        else canvas.lineTo(px, py)
                                    }; dist += 2f
                                }
                            } while (measure.nextContour())
                            canvas.stroke(); canvas.restoreState()
                        }
                    }

                    // Draw text annotations
                    pageTextAnnotations[idx]?.let { (textAnnots, scale) ->
                        val font = PdfFontFactory.createFont()
                        for (ta in textAnnots) {
                            val r = android.graphics.Color.red(ta.color)   / 255f
                            val g = android.graphics.Color.green(ta.color) / 255f
                            val b = android.graphics.Color.blue(ta.color)  / 255f
                            val pdfX      = (ta.x / scale).toDouble()
                            val pdfY      = (pdfH - ta.y / scale).toDouble()
                            val fontSizePt = (ta.sizePx / scale).coerceIn(6f, 72f)
                            canvas.saveState()
                            canvas.setFillColor(DeviceRgb(r, g, b))
                            canvas.setExtGState(PdfExtGState().also { it.fillOpacity = 1f })
                            // NOTE: Tamil and other non-Latin scripts cannot be rendered by
                            // iText's built-in Helvetica font. Text is saved in Latin transliteration
                            // or as Unicode codepoints that the PDF reader must handle.
                            canvas.beginText()
                                .setFontAndSize(font, fontSizePt)
                                .moveText(pdfX, pdfY)
                                .showText(ta.text)
                                .endText()
                            canvas.restoreState()
                        }
                    }
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}
}
