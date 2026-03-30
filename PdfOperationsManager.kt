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
                    // FIX: use timestamp to ensure unique filenames (prevents overwrite)
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

    // ---- WATERMARK (on top, not behind) ---------------------------------
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
                val page   = doc.getPage(i); val ps = page.pageSize
                val cx     = (ps.left + ps.right) / 2f; val cy = (ps.bottom + ps.top) / 2f
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
        inputFile: File, outputFile: File,
        format: String = "Page %d of %d", placement: String = "bottom"
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val doc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
        try {
            val font = PdfFontFactory.createFont(); val total = doc.numberOfPages
            for (i in 1..total) {
                val page = doc.getPage(i); val ps = page.pageSize
                var text = format.replaceFirst("%d", "$i")
                if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                val cx   = ((ps.left + ps.right) / 2.0) - (text.length * 5.0 * 0.5)
                val y    = if (placement == "top") (ps.top - 18.0) else (ps.bottom + 12.0)
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    canvas.beginText().setFontAndSize(font, 10f)
                        .moveText(cx, y).showText(text).endText()
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    // ---- HEADER / FOOTER ------------------------------------------------
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
                    // Use absolute setTextMatrix for each -- independent positioning
                    if (headerText != null) {
                        val hx = (ps.left + ps.right) / 2f - headerText.length * fontSize * 0.25f
                        canvas.beginText().setFontAndSize(font, fontSize)
                            .setTextMatrix(1f, 0f, 0f, 1f, hx, ps.top - 18f)
                            .showText(headerText).endText()
                    }
                    if (footerText != null) {
                        val fx = (ps.left + ps.right) / 2f - footerText.length * fontSize * 0.25f
                        canvas.beginText().setFontAndSize(font, fontSize)
                            .setTextMatrix(1f, 0f, 0f, 1f, fx, ps.bottom + 12f)
                            .showText(footerText).endText()
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
                    val imgW    = imgData.width.toFloat()
                    val imgH    = imgData.height.toFloat()
                    val ps      = PageSize(imgW, imgH)
                    pdfDoc.addNewPage(ps)
                    val img = Image(imgData)
                    img.setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                    img.setWidth(imgW); img.setHeight(imgH)
                    doc.add(img)
                }
            } finally { doc.close() }
            outputFile
        }}

    // ---- SAVE ANNOTATIONS -----------------------------------------------
    // TAMIL FIX: text annotations are rendered as Android Bitmap (full Unicode support)
    // and embedded as a PNG image in the PDF -- handles Tamil, Hindi, Arabic, all scripts
    suspend fun saveAnnotationsToPdf(
        inputFile: File,
        outputFile: File,
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
                val pdfW    = pdfPage.pageSize.width
                val pdfH    = pdfPage.pageSize.height
                val canvas  = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, doc)
                try {
                    // -- Stroke annotations --
                    pageAnnotations[idx]?.let { (strokes, scale) ->
                        for (stroke in strokes) {
                            if (stroke.tool == AnnotationCanvasView.TOOL_ERASER) continue
                            val paint = stroke.paint
                            val r = android.graphics.Color.red(paint.color)   / 255f
                            val g = android.graphics.Color.green(paint.color) / 255f
                            val b = android.graphics.Color.blue(paint.color)  / 255f
                            val a = (paint.alpha / 255f).coerceIn(0f, 1f)
                            canvas.saveState()
                            canvas.setExtGState(PdfExtGState().also {
                                it.strokeOpacity = a; it.fillOpacity = a
                            })
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

                    // -- Text annotations: render as Bitmap (FULL UNICODE / TAMIL SUPPORT) --
                    pageTextAnnotations[idx]?.let { (textAnnots, scale) ->
                        for (ta in textAnnots) {
                            // Render text to a Bitmap using Android's system font (supports all scripts)
                            val bmp = renderTextToBitmap(ta.text, ta.color, ta.sizePx)
                            val pngBytes = bitmapToPngBytes(bmp)
                            bmp.recycle()

                            val imgData   = ImageDataFactory.create(pngBytes)
                            val bmpW      = imgData.width.toFloat()
                            val bmpH      = imgData.height.toFloat()
                            // Convert screen coords to PDF coords
                            val pdfX      = ta.x / scale
                            val pdfY      = pdfH - (ta.y / scale) - (bmpH / scale)
                            val imgScaleW = bmpW / scale
                            val imgScaleH = bmpH / scale
                            canvas.saveState()
                            canvas.addImageWithTransformationMatrix(
                                imgData,
                                imgScaleW, 0f, 0f, imgScaleH, pdfX, pdfY
                            )
                            canvas.restoreState()
                        }
                    }
                } finally { canvas.release() }
            }
        } finally { doc.close() }
        outputFile
    }}

    /** Render any text (including Tamil/Hindi/Arabic) to a Bitmap using Android system fonts. */
    private fun renderTextToBitmap(text: String, color: Int, sizePx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color    = color
            textSize      = sizePx.coerceAtLeast(12f)
            typeface      = Typeface.DEFAULT
            isLinearText  = true
            isSubpixelText = true
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val w = (bounds.width() + sizePx).toInt().coerceAtLeast(4)
        val h = (bounds.height() + sizePx * 0.5f).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        cvs.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        cvs.drawText(text, sizePx * 0.2f, bounds.height().toFloat() + sizePx * 0.1f, paint)
        return bmp
    }

    /** Convert Bitmap to PNG byte array for iText. */
    private fun bitmapToPngBytes(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    // ---- RESHAPE PAGE SIZE ------------------------------------------
    // Exports PDF with all pages scaled/cropped to a target page size
    suspend fun reshapePageSize(
        inputFile: File, outputFile: File,
        pageWidthPt: Float, pageHeightPt: Float
    ): Result<File> = withContext(Dispatchers.IO) { runCatching {
        val src  = PdfDocument(PdfReader(inputFile.absolutePath))
        val dest = PdfDocument(PdfWriter(outputFile.absolutePath))
        val doc  = Document(dest)
        try {
            val targetSize = PageSize(pageWidthPt, pageHeightPt)
            for (i in 1..src.numberOfPages) {
                val srcPage  = src.getPage(i)
                val destPage = dest.addNewPage(targetSize)
                // Scale source page content to fit target size
                val srcW  = srcPage.pageSize.width
                val srcH  = srcPage.pageSize.height
                val scaleX = pageWidthPt  / srcW
                val scaleY = pageHeightPt / srcH
                val scale  = minOf(scaleX, scaleY)
                val offX   = (pageWidthPt  - srcW  * scale) / 2f
                val offY   = (pageHeightPt - srcH  * scale) / 2f
                val canvas = PdfCanvas(destPage, dest)
                try {
                    val xobj = srcPage.copyAsFormXObject(dest)
                    canvas.addXObjectWithTransformationMatrix(xobj,
                        scale.toDouble(), 0.0, 0.0, scale.toDouble(),
                        offX.toDouble(), offY.toDouble())
                } finally { canvas.release() }
            }
        } finally { doc.close(); src.close() }
        outputFile
    }}

}