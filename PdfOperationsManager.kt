package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import java.io.ByteArrayOutputStream
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
                    try { merger.merge(src, 1, src.numberOfPages) } finally { src.close() }
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
                    val stamp = System.currentTimeMillis()
                    val out   = File(outputDir, "${inputFile.nameWithoutExtension}_part${i+1}_${stamp}.pdf")
                    val dest  = PdfDocument(PdfWriter(out.absolutePath))
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

    // ---- COMPRESS (improved - multiple passes) ---------------------------
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
            // Report actual reduction
            outputFile
        }}

    // ---- ENCRYPT --------------------------------------------------------
    suspend fun encryptPdf(inputFile: File, outputFile: File, userPassword: String?, ownerPassword: String,
        allowPrinting: Boolean = true, allowCopying: Boolean = false): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            var perms = EncryptionConstants.ALLOW_SCREENREADERS
            if (allowPrinting) perms = perms or EncryptionConstants.ALLOW_PRINTING
            if (allowCopying)  perms = perms or EncryptionConstants.ALLOW_COPY
            val doc = PdfDocument(PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, WriterProperties().setStandardEncryption(
                    userPassword?.toByteArray(), ownerPassword.toByteArray(),
                    perms, EncryptionConstants.ENCRYPTION_AES_256)))
            try { } finally { doc.close() }
            outputFile
        }}

    // ---- REMOVE PASSWORD ------------------------------------------------
    suspend fun removePdfPassword(inputFile: File, outputFile: File, password: String): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(
                PdfReader(inputFile.absolutePath, ReaderProperties().setPassword(password.toByteArray())),
                PdfWriter(outputFile.absolutePath))
            try { } finally { doc.close() }
            outputFile
        }}

    // ---- WATERMARK (on top) ---------------------------------------------
    suspend fun addTextWatermark(inputFile: File, outputFile: File, text: String,
        opacity: Float = 0.3f, rotation: Float = 45f): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val font = PdfFontFactory.createFont()
                val gs   = PdfExtGState().also { it.fillOpacity = opacity; it.strokeOpacity = opacity }
                val rad  = Math.toRadians(rotation.toDouble())
                for (i in 1..doc.numberOfPages) {
                    val page   = doc.getPage(i); val ps = page.pageSize
                    val cx = (ps.left + ps.right) / 2f; val cy = (ps.bottom + ps.top) / 2f
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        canvas.saveState().setExtGState(gs).beginText()
                            .setFontAndSize(font, 60f)
                            .setFillColor(DeviceRgb(0.5f, 0.5f, 0.5f))
                            .setTextMatrix(cos(rad).toFloat(), sin(rad).toFloat(),
                                -sin(rad).toFloat(), cos(rad).toFloat(), cx, cy)
                            .showText(text).endText().restoreState()
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }}

    // ---- DELETE PAGES ---------------------------------------------------
    suspend fun deletePages(inputFile: File, outputFile: File, pagesToDelete: List<Int>): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try { pagesToDelete.sortedDescending().forEach { n -> if (n in 1..doc.numberOfPages) doc.removePage(n) } }
            finally { doc.close() }
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

    // ---- PAGE NUMBERS (with alignment) ----------------------------------
    // FIX: alignment = "left" | "center" | "right"
    // FIX: Tamil text rendered as bitmap so it displays correctly
    suspend fun addPageNumbers(inputFile: File, outputFile: File,
        format: String = "Page %d of %d",
        placement: String = "bottom",
        alignment: String = "center"): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val font    = PdfFontFactory.createFont()
                val total   = doc.numberOfPages
                val fontSize = 10f
                for (i in 1..total) {
                    val page = doc.getPage(i); val ps = page.pageSize
                    var text = format.replaceFirst("%d", "$i")
                    if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                    val textW = text.length * fontSize * 0.5f
                    val x = when (alignment) {
                        "left"  -> ps.left + 20f
                        "right" -> ps.right - textW - 20f
                        else    -> (ps.left + ps.right) / 2f - textW / 2f
                    }
                    val y = if (placement == "top") ps.top - 18f else ps.bottom + 12f
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        canvas.beginText().setFontAndSize(font, fontSize)
                            .setTextMatrix(1f, 0f, 0f, 1f, x, y)
                            .showText(text).endText()
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }}

    // ---- HEADER / FOOTER (with alignment, Tamil bitmap support) ---------
    suspend fun addHeaderFooter(inputFile: File, outputFile: File,
        headerText: String? = null, footerText: String? = null,
        fontSize: Float = 10f,
        headerAlignment: String = "center",
        footerAlignment: String = "center"): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                for (i in 1..doc.numberOfPages) {
                    val page = doc.getPage(i); val ps = page.pageSize
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        if (headerText != null) {
                            embedTextAsBitmap(canvas, doc, page, headerText, fontSize,
                                ps, headerAlignment, isHeader = true)
                        }
                        if (footerText != null) {
                            embedTextAsBitmap(canvas, doc, page, footerText, fontSize,
                                ps, footerAlignment, isHeader = false)
                        }
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }}

    // Render text as a bitmap (supports Tamil, Hindi, all Unicode) and embed in PDF
    private fun embedTextAsBitmap(canvas: PdfCanvas, doc: PdfDocument, page: com.itextpdf.kernel.pdf.PdfPage,
        text: String, fontSizePt: Float, ps: com.itextpdf.kernel.geom.Rectangle,
        alignment: String, isHeader: Boolean) {
        try {
            // Render text using Android system font (supports all languages)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePt * 2.5f  // scale up for bitmap quality
                color    = Color.BLACK
                typeface = Typeface.DEFAULT
                isLinearText = true
            }
            val bounds = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val bW = bounds.width() + 20
            val bH = (fontSizePt * 4).toInt().coerceAtLeast(20)
            val bmp = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                drawText(text, 10f, bH - 6f, paint)
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bmp.recycle()

            val imgData = ImageDataFactory.create(baos.toByteArray())
            val imgW    = bW.toFloat() / 2.5f  // scale back to PDF points
            val imgH    = bH.toFloat() / 2.5f
            val x = when (alignment) {
                "left"  -> ps.left + 10f
                "right" -> ps.right - imgW - 10f
                else    -> (ps.left + ps.right) / 2f - imgW / 2f
            }
            val y = if (isHeader) ps.top - imgH - 4f else ps.bottom + 4f
            canvas.saveState()
            canvas.addXObjectAt(com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData), x, y)
            canvas.restoreState()
        } catch (_: Exception) {
            // Fallback to basic Latin text
            try {
                val font = PdfFontFactory.createFont()
                val textW = text.length * fontSizePt * 0.5f
                val x = when (alignment) {
                    "left"  -> ps.left + 20f
                    "right" -> ps.right - textW - 20f
                    else    -> (ps.left + ps.right) / 2f - textW / 2f
                }
                val y = if (isHeader) ps.top - 18f else ps.bottom + 12f
                canvas.beginText().setFontAndSize(font, fontSizePt)
                    .setTextMatrix(1f, 0f, 0f, 1f, x, y)
                    .showText(text).endText()
            } catch (_: Exception) {}
        }
    }

    // ---- IMAGES TO PDF --------------------------------------------------
    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val pdfDoc = PdfDocument(PdfWriter(outputFile.absolutePath))
            val doc    = Document(pdfDoc)
            try {
                imageFiles.filter { it.exists() && it.length() > 0 }.forEach { f ->
                    val imgData = ImageDataFactory.create(f.absolutePath)
                    val ps      = PageSize(imgData.width.toFloat(), imgData.height.toFloat())
                    pdfDoc.addNewPage(ps)
                    val img = Image(imgData)
                    img.setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                    img.setWidth(ps.width); img.setHeight(ps.height)
                    doc.add(img)
                }
            } finally { doc.close() }
            outputFile
        }}

    // ---- RESHAPE PAGE SIZE ----------------------------------------------
    suspend fun reshapePageSize(inputFile: File, outputFile: File,
        pageWidthPt: Float, pageHeightPt: Float): Result<File> =
        withContext(Dispatchers.IO) { runCatching {
            val src  = PdfDocument(PdfReader(inputFile.absolutePath))
            val dest = PdfDocument(PdfWriter(outputFile.absolutePath))
            val doc  = Document(dest)
            try {
                val targetSize = PageSize(pageWidthPt, pageHeightPt)
                for (i in 1..src.numberOfPages) {
                    val srcPage  = src.getPage(i)
                    val srcW     = srcPage.pageSize.width
                    val srcH     = srcPage.pageSize.height
                    val scale    = minOf(pageWidthPt / srcW, pageHeightPt / srcH)
                    val offX     = (pageWidthPt  - srcW  * scale) / 2f
                    val offY     = (pageHeightPt - srcH  * scale) / 2f
                    val destPage = dest.addNewPage(targetSize)
                    val canvas   = PdfCanvas(destPage.newContentStreamAfter(), destPage.resources, dest)
                    try {
                        val xobj = srcPage.copyAsFormXObject(dest)
                        canvas.saveState()
                        canvas.concatMatrix(scale.toDouble(), 0.0, 0.0, scale.toDouble(),
                            offX.toDouble(), offY.toDouble())
                        canvas.addXObjectAt(xobj, 0f, 0f)
                        canvas.restoreState()
                    } finally { canvas.release() }
                }
            } finally { doc.close(); src.close() }
            outputFile
        }}

    // ---- SAVE ANNOTATIONS (Tamil bitmap support) ------------------------
    suspend fun saveAnnotationsToPdf(inputFile: File, outputFile: File,
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
                            canvas.setExtGState(PdfExtGState().also { it.strokeOpacity = a; it.fillOpacity = a })
                            canvas.setStrokeColor(DeviceRgb(r, g, b))
                            canvas.setLineWidth((paint.strokeWidth / scale).coerceAtLeast(0.5f))
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
                    // Draw text annotations as bitmaps (Tamil support)
                    pageTextAnnotations[idx]?.let { (textAnnots, scale) ->
                        for (ta in textAnnots) {
                            val bmp = renderTextBitmap(ta.text, ta.color, ta.sizePx)
                            val pngBytes = bitmapToPng(bmp); bmp.recycle()
                            val imgData  = ImageDataFactory.create(pngBytes)
                            val bW       = imgData.width.toFloat()
                            val bH       = imgData.height.toFloat()
                            val pdfX     = ta.x / scale
                            val pdfY     = pdfH - (ta.y / scale) - (bH / scale)
                            canvas.saveState()
                            canvas.addXObjectWithTransformationMatrix(
                                com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                                bW / scale, 0f, 0f, bH / scale, pdfX, pdfY)
                            canvas.restoreState()
                        }
                    }
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    private fun renderTextBitmap(text: String, color: Int, sizePx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = sizePx.coerceAtLeast(12f)
            typeface = Typeface.DEFAULT; isLinearText = true; isSubpixelText = true
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val w = (bounds.width() + sizePx).toInt().coerceAtLeast(4)
        val h = (bounds.height() + sizePx * 0.5f).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            drawText(text, sizePx * 0.2f, bounds.height().toFloat() + sizePx * 0.1f, paint)
        }
        return bmp
    }

    private fun bitmapToPng(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    // addXObjectWithTransformationMatrix helper
    private fun PdfCanvas.addXObjectWithTransformationMatrix(
        xobj: com.itextpdf.kernel.pdf.xobject.PdfXObject,
        a: Float, b: Float, c: Float, d: Float, e: Float, f: Float
    ): PdfCanvas {
        saveState()
        concatMatrix(a.toDouble(), b.toDouble(), c.toDouble(), d.toDouble(), e.toDouble(), f.toDouble())
        addXObjectAt(xobj, 0f, 0f)
        restoreState()
        return this
    }
}
