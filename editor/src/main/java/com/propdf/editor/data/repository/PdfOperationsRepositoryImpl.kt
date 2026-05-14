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
import com.itextpdf.kernel.pdf.PdfDocument as IPdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class PdfOperationsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : PdfOperationsRepository {

    companion object {
        private const val TAG = "PdfOpsRepo"
    }

    // ===================== MERGE =====================
    override suspend fun merge(request: MergeRequest, outputFile: File): AppResult<File> =
        withContext(dispatchers.io) {
            runCatching {
                val out = IPdfDocument(PdfWriter(outputFile.absolutePath))
                try {
                    val merger = PdfMerger(out)
                    request.inputUris.map { File(it) }.filter { it.exists() }.forEach { f ->
                        val src = IPdfDocument(PdfReader(f.absolutePath))
                        try { merger.merge(src, 1, src.numberOfPages) } finally { src.close() }
                    }
                } finally { out.close() }
                outputFile
            }.toAppResult()
        }

    // ===================== SPLIT =====================
    override suspend fun split(request: SplitRequest): AppResult<List<File>> =
        withContext(dispatchers.io) {
            runCatching {
                val result = mutableListOf<File>()
                val src = IPdfDocument(PdfReader(request.inputUri))
                try {
                    request.ranges.forEachIndexed { i, range ->
                        val out = File(request.outputDir, "part${i + 1}_${System.currentTimeMillis()}.pdf")
                        val dest = IPdfDocument(PdfWriter(out.absolutePath))
                        try {
                            PdfMerger(dest).merge(src,
                                range.first.coerceIn(1, src.numberOfPages),
                                range.last.coerceIn(1, src.numberOfPages))
                        } finally { dest.close() }
                        result.add(out)
                    }
                } finally { src.close() }
                result
            }.toAppResult()
        }

    // ===================== COMPRESS =====================
    override suspend fun compress(
        inputFile: File,
        outputFile: File,
        config: CompressConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val props = WriterProperties().apply {
                setCompressionLevel(config.level.coerceIn(1, 9))
                setFullCompressionMode(true)
                useSmartMode()
            }
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, props))
            try { } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== ENCRYPT =====================
    override suspend fun encrypt(
        inputFile: File,
        outputFile: File,
        config: SecurityConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            var perms = EncryptionConstants.ALLOW_SCREENREADERS
            if (config.allowPrinting) perms = perms or EncryptionConstants.ALLOW_PRINTING
            if (config.allowCopying) perms = perms or EncryptionConstants.ALLOW_COPY
            val doc = IPdfDocument(
                PdfReader(inputFile.absolutePath),
                PdfWriter(outputFile.absolutePath, WriterProperties().setStandardEncryption(
                    config.userPassword?.toByteArray(),
                    config.ownerPassword.toByteArray(),
                    perms,
                    EncryptionConstants.ENCRYPTION_AES_256
                ))
            )
            try { } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== DECRYPT =====================
    override suspend fun decrypt(inputFile: File, outputFile: File, password: String): AppResult<File> =
        withContext(dispatchers.io) {
            runCatching {
                val doc = IPdfDocument(
                    PdfReader(inputFile.absolutePath, ReaderProperties().setPassword(password.toByteArray())),
                    PdfWriter(outputFile.absolutePath)
                )
                try { } finally { doc.close() }
                outputFile
            }.toAppResult()
        }

    // ===================== WATERMARK =====================
    override suspend fun addWatermark(
        inputFile: File,
        outputFile: File,
        config: WatermarkConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val font = PdfFontFactory.createFont()
                val gs = PdfExtGState().apply { fillOpacity = config.opacity; strokeOpacity = config.opacity }
                val rad = Math.toRadians(config.rotation.toDouble())
                for (i in 1..doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val ps = page.pageSize
                    val cx = (ps.left + ps.right) / 2f
                    val cy = (ps.bottom + ps.top) / 2f
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        canvas.saveState().setExtGState(gs).beginText()
                            .setFontAndSize(font, config.fontSize)
                            .setFillColor(DeviceRgb(0.5f, 0.5f, 0.5f))
                            .setTextMatrix(cos(rad).toFloat(), sin(rad).toFloat(),
                                -sin(rad).toFloat(), cos(rad).toFloat(), cx, cy)
                            .showText(config.text).endText().restoreState()
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== DELETE PAGES =====================
    override suspend fun deletePages(
        inputFile: File,
        outputFile: File,
        pages: List<Int>
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                pages.sortedDescending().forEach { n ->
                    if (n in 1..doc.numberOfPages) doc.removePage(n)
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== ROTATE =====================
    override suspend fun rotatePages(
        inputFile: File,
        outputFile: File,
        rotations: Map<Int, Int>
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                rotations.forEach { (n, deg) ->
                    if (n in 1..doc.numberOfPages) {
                        val p = doc.getPage(n)
                        p.put(PdfName.Rotate, PdfNumber((p.rotation + deg) % 360))
                    }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== PAGE NUMBERS =====================
    override suspend fun addPageNumbers(
        inputFile: File,
        outputFile: File,
        config: PageNumberConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val font = PdfFontFactory.createFont()
                val total = doc.numberOfPages
                for (i in 1..total) {
                    val page = doc.getPage(i)
                    val ps = page.pageSize
                    var text = config.format.replaceFirst("%d", "$i")
                    if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                    val textW = text.length * config.fontSize * 0.5f
                    val x = when (config.alignment) {
                        "left" -> ps.left + 20f
                        "right" -> ps.right - textW - 20f
                        else -> (ps.left + ps.right) / 2f - textW / 2f
                    }
                    val y = if (config.placement == "top") ps.top - 18f else ps.bottom + 12f
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        canvas.beginText().setFontAndSize(font, config.fontSize)
                            .setTextMatrix(1f, 0f, 0f, 1f, x, y)
                            .showText(text).endText()
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== HEADER/FOOTER =====================
    override suspend fun addHeaderFooter(
        inputFile: File,
        outputFile: File,
        config: HeaderFooterConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                for (i in 1..doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val ps = page.pageSize
                    val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                    try {
                        config.headerText?.let { embedTextAsBitmap(canvas, doc, page, it, config.fontSize, ps, config.headerAlignment, true) }
                        config.footerText?.let { embedTextAsBitmap(canvas, doc, page, it, config.fontSize, ps, config.footerAlignment, false) }
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== IMAGES TO PDF =====================
    override suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): AppResult<File> =
        withContext(dispatchers.io) {
            runCatching {
                val pdfDoc = IPdfDocument(PdfWriter(outputFile.absolutePath))
                val doc = Document(pdfDoc)
                try {
                    imageFiles.filter { it.exists() && it.length() > 0 }.forEach { f ->
                        val imgData = ImageDataFactory.create(f.absolutePath)
                        val ps = PageSize(imgData.width.toFloat(), imgData.height.toFloat())
                        pdfDoc.addNewPage(ps)
                        val img = Image(imgData)
                        img.setFixedPosition(pdfDoc.numberOfPages, 0f, 0f)
                        img.setWidth(ps.width)
                        img.setHeight(ps.height)
                        doc.add(img)
                    }
                } finally { doc.close() }
                outputFile
            }.toAppResult()
        }

    // ===================== INSERT IMAGE =====================
    override suspend fun insertImageOnPage(
        inputFile: File,
        imageFile: File,
        pageNum: Int,
        x: Float,
        y: Float
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val outputFile = File(inputFile.parent, "output_${System.currentTimeMillis()}.pdf")
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val page = doc.getPage(pageNum.coerceIn(1, doc.numberOfPages))
                val ps = page.pageSize
                val imgData = ImageDataFactory.create(imageFile.absolutePath)
                val imgW = imgData.width
                val imgH = imgData.height
                val finalX = if (x > 0) x else (ps.right - imgW - 30f).coerceAtLeast(10f)
                val finalY = if (y > 0) y else (ps.top - imgH - 80f).coerceAtLeast(10f)
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    val xobj = com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData)
                    canvas.saveState()
                    canvas.addXObjectAt(xobj, finalX, finalY)
                    canvas.restoreState()
                } finally { canvas.release() }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== RESHAPE =====================
    override suspend fun reshapePageSize(
        inputFile: File,
        outputFile: File,
        widthPt: Float,
        heightPt: Float
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val src = IPdfDocument(PdfReader(inputFile.absolutePath))
            val dest = IPdfDocument(PdfWriter(outputFile.absolutePath))
            val doc = Document(dest)
            try {
                val targetSize = PageSize(widthPt, heightPt)
                for (i in 1..src.numberOfPages) {
                    val srcPage = src.getPage(i)
                    val srcW = srcPage.pageSize.width.toFloat()
                    val srcH = srcPage.pageSize.height.toFloat()
                    val scale = kotlin.math.min(widthPt / srcW, heightPt / srcH)
                    val offX = (widthPt - srcW * scale) / 2f
                    val offY = (heightPt - srcH * scale) / 2f
                    val destPage = dest.addNewPage(targetSize)
                    val canvas = PdfCanvas(destPage.newContentStreamAfter(), destPage.resources, dest)
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
        }.toAppResult()
    }

    // ===================== SAVE ANNOTATIONS =====================
    override suspend fun saveAnnotations(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<<AnnotationStroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<<AnnotationText>, Float>>
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = IPdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val allPages = (pageAnnotations.keys + pageTextAnnotations.keys).toSet()
                for (idx in allPages) {
                    val pdfPageNum = idx + 1
                    if (pdfPageNum > doc.numberOfPages) continue
                    val pdfPage = doc.getPage(pdfPageNum)
                    val pdfH = pdfPage.pageSize.height
                    val canvas = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, doc)
                    try {
                        // Draw strokes
                        pageAnnotations[idx]?.let { (strokes, scale) ->
                            for (stroke in strokes) {
                                if (stroke.tool == "eraser") continue
                                val r = android.graphics.Color.red(stroke.color) / 255f
                                val g = android.graphics.Color.green(stroke.color) / 255f
                                val b = android.graphics.Color.blue(stroke.color) / 255f
                                val a = (android.graphics.Color.alpha(stroke.color) / 255f).coerceIn(0f, 1f)
                                canvas.saveState()
                                canvas.setExtGState(PdfExtGState().apply { strokeOpacity = a; fillOpacity = a })
                                canvas.setStrokeColor(DeviceRgb(r, g, b))
                                canvas.setLineWidth((stroke.strokeWidth / scale).coerceAtLeast(0.5f))
                                canvas.setLineCapStyle(1)
                                canvas.setLineJoinStyle(1)
                                // Draw path from points
                                if (stroke.pathData.isNotEmpty()) {
                                    val first = stroke.pathData.first()
                                    canvas.moveTo((first.x / scale).toDouble(), (pdfH - first.y / scale).toDouble())
                                    stroke.pathData.drop(1).forEach { pt ->
                                        canvas.lineTo((pt.x / scale).toDouble(), (pdfH - pt.y / scale).toDouble())
                                    }
                                }
                                canvas.stroke()
                                canvas.restoreState()
                            }
                        }
                        // Draw text annotations
                        pageTextAnnotations[idx]?.let { (textAnnots, scale) ->
                            for (ta in textAnnots) {
                                val bmp = renderTextBitmap(ta.text, ta.color, ta.sizePx)
                                val pngBytes = bitmapToPng(bmp)
                                bmp.recycle()
                                val imgData = ImageDataFactory.create(pngBytes)
                                val bW = imgData.width.toFloat()
                                val bH = imgData.height.toFloat()
                                val pdfX = ta.x / scale
                                val pdfY = pdfH - (ta.y / scale) - (bH / scale)
                                canvas.saveState()
                                canvas.addXObjectWithTransformationMatrix(
                                    com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                                    bW / scale, 0f, 0f, bH / scale, pdfX, pdfY
                                )
                                canvas.restoreState()
                            }
                        }
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== PRIVATE HELPERS =====================
    private fun embedTextAsBitmap(
        canvas: PdfCanvas, doc: IPdfDocument, page: com.itextpdf.kernel.pdf.PdfPage,
        text: String, fontSizePt: Float, ps: com.itextpdf.kernel.geom.Rectangle,
        alignment: String, isHeader: Boolean
    ) {
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePt * 2.5f
                color = Color.BLACK
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
            val imgW = bW.toFloat() / 2.5f
            val imgH = bH.toFloat() / 2.5f
            val x = when (alignment) {
                "left" -> ps.left + 10f
                "right" -> ps.right - imgW - 10f
                else -> (ps.left + ps.right) / 2f - imgW / 2f
            }
            val y = if (isHeader) ps.top - imgH - 4f else ps.bottom + 4f
            canvas.saveState()
            canvas.addXObjectAt(com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData), x, y)
            canvas.restoreState()
        } catch (_: Exception) {
            // Fallback
            try {
                val font = PdfFontFactory.createFont()
                val textW = text.length * fontSizePt * 0.5f
                val x = when (alignment) {
                    "left" -> ps.left + 20f
                    "right" -> ps.right - textW - 20f
                    else -> (ps.left + ps.right) / 2f - textW / 2f
                }
                val y = if (isHeader) ps.top - 18f else ps.bottom + 12f
                canvas.beginText().setFontAndSize(font, fontSizePt)
                    .setTextMatrix(1f, 0f, 0f, 1f, x, y)
                    .showText(text).endText()
            } catch (_: Exception) {}
        }
    }

    private fun renderTextBitmap(text: String, color: Int, sizePx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx.coerceAtLeast(12f)
            typeface = Typeface.DEFAULT
            isLinearText = true
            isSubpixelText = true
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
