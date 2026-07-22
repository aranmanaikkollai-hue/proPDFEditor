package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
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
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.PdfProcessingError
import com.propdf.core.domain.result.toAppException
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
                val out = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(outputFile.absolutePath))
                try {
                    val merger = PdfMerger(out)
                    request.inputUris.mapNotNull { it.path?.let(::File) }.filter { it.exists() }.forEach { f ->
                        val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(f.absolutePath))
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
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(request.inputUri))
                try {
                    request.ranges.forEachIndexed { i, range ->
                        val out = File(request.outputDir, "part${i + 1}_${System.currentTimeMillis()}.pdf")
                        val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(out.absolutePath))
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, props))
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(
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
                val doc = com.itextpdf.kernel.pdf.PdfDocument(
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
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
        rotations: Map<Int, Float>
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                rotations.forEach { (n, deg) ->
                    if (n in 1..doc.numberOfPages) {
                        val p = doc.getPage(n)
                        p.put(PdfName.Rotate, PdfNumber((p.rotation + deg.toInt()) % 360))
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
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
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
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
                val pdfDoc = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(outputFile.absolutePath))
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
        outputFile: File,
        config: ImageInsertionConfig
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val page = doc.getPage(doc.numberOfPages)
                val ps = page.pageSize
                val imageFile = File(config.imageUri.path ?: throw PdfProcessingError.ProcessingFailed("Invalid image URI"))
                val imgData = ImageDataFactory.create(imageFile.absolutePath)
                val imgW = imgData.width.toFloat()
                val imgH = imgData.height.toFloat()

                val availW = (ps.width - 2 * config.margin).coerceAtLeast(1f)
                val availH = (ps.height - 2 * config.margin).coerceAtLeast(1f)
                val (drawW, drawH) = when (config.fitMode) {
                    ImageFitMode.FILL -> availW to availH
                    ImageFitMode.FIT_WIDTH -> availW to (availW * imgH / imgW)
                    ImageFitMode.FIT_HEIGHT -> (availH * imgW / imgH) to availH
                    ImageFitMode.ORIGINAL -> imgW to imgH
                    ImageFitMode.FIT_CENTER -> {
                        val scale = kotlin.math.min(availW / imgW, availH / imgH)
                        (imgW * scale) to (imgH * scale)
                    }
                }
                val finalX = ps.left + (ps.width - drawW) / 2f
                val finalY = ps.bottom + (ps.height - drawH) / 2f

                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                try {
                    val xobj = com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData)
                    canvas.saveState()
                    canvas.addXObjectWithTransformationMatrix(xobj, drawW, 0f, 0f, drawH, finalX, finalY)
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
            val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath))
            val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(outputFile.absolutePath))
            val doc = Document(dest)
            try {
                val targetSize = PageSize(widthPt, heightPt)
                for (i in 1..src.numberOfPages) {
                    val srcPage = src.getPage(i)
                    val srcW = srcPage.pageSize.width
                    val srcH = srcPage.pageSize.height
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
        pageAnnotations: Map<Int, Pair<List<AnnotationStroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<AnnotationText>, Float>>
    ): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
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

    // ===================== EXTRACT PAGES AS IMAGES =====================
    override suspend fun extractPagesAsImages(
        inputFile: File,
        pages: List<Int>?
    ): AppResult<List<Bitmap>> = withContext(dispatchers.io) {
        runCatching {
            val result = mutableListOf<Bitmap>()
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath))
            try {
                val pageList = pages ?: (1..doc.numberOfPages).toList()
                for (pageNum in pageList) {
                    if (pageNum !in 1..doc.numberOfPages) continue
                    val pdfPage = doc.getPage(pageNum)
                    val pageSize = pdfPage.pageSize
                    val width = pageSize.width.toInt()
                    val height = pageSize.height.toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    // Use PdfPageFormCopier or render via PdfCanvas
                    // For now, create a blank canvas and draw
                    canvas.drawColor(Color.WHITE)
                    result.add(bitmap)
                }
            } finally { doc.close() }
            result
        }.toAppResult()
    }

    // ===================== RENDER PAGE TO BITMAP =====================
    override suspend fun renderPageToBitmap(
        inputFile: File,
        pageNum: Int,
        width: Int?
    ): AppResult<Bitmap> = withContext(dispatchers.io) {
        runCatching {
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath))
            try {
                if (pageNum !in 1..doc.numberOfPages) {
                    throw PdfProcessingError.InvalidPage("Page $pageNum not in range 1..${doc.numberOfPages}")
                }
                val pdfPage = doc.getPage(pageNum)
                val pageSize = pdfPage.pageSize
                val targetWidth = width ?: pageSize.width.toInt()
                val scale = targetWidth.toFloat() / pageSize.width
                val targetHeight = (pageSize.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                // Note: Full PDF rendering requires PdfRenderer or similar
                // This is a placeholder implementation
                bitmap
            } finally { doc.close() }
        }.toAppResult()
    }

    // ===================== URI-BASED PAGE OPERATIONS (used by PdfOperationWorker) =====================

    /** Converts a File-based AppResult into a Uri-based one by wrapping the resulting file. */
    private fun AppResult<File>.toUriResult(): AppResult<Uri> = when (this) {
        is AppResult.Success -> AppResult.Success(Uri.fromFile(data))
        is AppResult.Error -> this
        is AppResult.Loading -> AppResult.Loading(progress)
    }

    override suspend fun getPageCount(sourceUri: Uri): AppResult<Int> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val count = doc.numberOfPages
                doc.close()
                count
            }.toAppResult()
        }

    override suspend fun getPageThumbnails(sourceUri: Uri, pages: List<Int>): AppResult<List<Bitmap>> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val pfd = android.os.ParcelFileDescriptor.open(input, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    pages.mapNotNull { pageNum ->
                        val index = pageNum - 1
                        if (index !in 0 until renderer.pageCount) return@mapNotNull null
                        renderer.openPage(index).use { page ->
                            val width = 200
                            val ratio = width.toFloat() / page.width
                            val height = (page.height * ratio).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
            }.toAppResult()
        }

    override suspend fun mergePdfs(config: MergeConfig): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val output = createOutputFile(config.outputFileName.ifBlank { "merged" })
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val merger = PdfMerger(dest)
                    config.sourceUris.forEach { uri ->
                        val file = resolveUriToFile(uri)
                        val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(file.absolutePath))
                        try { merger.merge(src, 1, src.numberOfPages) } finally { src.close() }
                    }
                } finally { dest.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun insertPdfPages(
        sourceUri: Uri,
        insertUri: Uri,
        position: Int,
        sourcePages: List<Int>
    ): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val mainInput = resolveUriToFile(sourceUri)
                val insertInput = resolveUriToFile(insertUri)
                val output = createOutputFile("inserted_pdf")
                val main = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(mainInput.absolutePath))
                val toInsert = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(insertInput.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val insertAt = position.coerceIn(1, main.numberOfPages + 1)
                    val merger = PdfMerger(dest)
                    if (insertAt > 1) merger.merge(main, 1, insertAt - 1)
                    val pagesToInsert = sourcePages.ifEmpty { (1..toInsert.numberOfPages).toList() }
                    pagesToInsert.filter { it in 1..toInsert.numberOfPages }.forEach { p -> merger.merge(toInsert, p, p) }
                    if (insertAt <= main.numberOfPages) merger.merge(main, insertAt, main.numberOfPages)
                } finally { dest.close(); toInsert.close(); main.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun deletePages(sourceUri: Uri, pages: List<Int>): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return deletePages(input, createOutputFile("deleted"), pages).toUriResult()
    }

    override suspend fun duplicatePages(sourceUri: Uri, pages: List<Int>): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("duplicated")
                val dupSet = pages.toSet()
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val merger = PdfMerger(dest)
                    for (i in 1..src.numberOfPages) {
                        merger.merge(src, i, i)
                        if (i in dupSet) merger.merge(src, i, i) // insert a copy right after the original
                    }
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun movePages(sourceUri: Uri, pages: List<Int>, target: Int): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("moved")
                val countProbe = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val pageCount = countProbe.numberOfPages
                countProbe.close()

                val moving = pages.filter { it in 1..pageCount }.distinct()
                val remaining = (1..pageCount).filterNot { it in moving }
                val insertAt = target.coerceIn(0, remaining.size)
                val newOrder = remaining.subList(0, insertAt) + moving + remaining.subList(insertAt, remaining.size)

                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val merger = PdfMerger(dest)
                    newOrder.forEach { pageNum -> merger.merge(src, pageNum, pageNum) }
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun extractPages(sourceUri: Uri, pages: List<Int>, outputName: String): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile(outputName.ifBlank { "extracted" })
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val merger = PdfMerger(dest)
                    pages.filter { it in 1..src.numberOfPages }.forEach { p -> merger.merge(src, p, p) }
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun rotatePages(sourceUri: Uri, pages: List<Int>, degrees: Int): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        val rotations = pages.associateWith { degrees.toFloat() }
        return rotatePages(input, createOutputFile("rotated"), rotations).toUriResult()
    }

    override suspend fun cropPages(sourceUri: Uri, pages: List<Int>, config: CropConfig): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("cropped")
                val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath), PdfWriter(output.absolutePath))
                try {
                    val left = toPoints(config.leftMargin, config.unit)
                    val right = toPoints(config.rightMargin, config.unit)
                    val top = toPoints(config.topMargin, config.unit)
                    val bottom = toPoints(config.bottomMargin, config.unit)
                    val targets = if (pages.isEmpty()) (1..doc.numberOfPages).toList() else pages
                    targets.forEach { n ->
                        if (n in 1..doc.numberOfPages) {
                            val page = doc.getPage(n)
                            val box = page.mediaBox
                            page.cropBox = com.itextpdf.kernel.geom.Rectangle(
                                box.left + left,
                                box.bottom + bottom,
                                (box.width - left - right).coerceAtLeast(1f),
                                (box.height - top - bottom).coerceAtLeast(1f)
                            )
                        }
                    }
                } finally { doc.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun resizePages(sourceUri: Uri, pages: List<Int>, config: ResizeConfig): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("resized")
                val targetW = toPoints(config.targetWidth, config.unit)
                val targetH = toPoints(config.targetHeight, config.unit)
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val targets = if (pages.isEmpty()) (1..src.numberOfPages).toSet() else pages.toSet()
                    val merger = PdfMerger(dest)
                    for (i in 1..src.numberOfPages) {
                        if (i !in targets) {
                            merger.merge(src, i, i)
                            continue
                        }
                        val srcPage = src.getPage(i)
                        val srcW = srcPage.pageSize.width
                        val srcH = srcPage.pageSize.height
                        val scaleX = if (config.keepAspectRatio) kotlin.math.min(targetW / srcW, targetH / srcH) else targetW / srcW
                        val scaleY = if (config.keepAspectRatio) scaleX else targetH / srcH
                        val destPage = dest.addNewPage(PageSize(targetW, targetH))
                        if (config.scaleContent) {
                            val offX = (targetW - srcW * scaleX) / 2f
                            val offY = (targetH - srcH * scaleY) / 2f
                            val canvas = PdfCanvas(destPage.newContentStreamAfter(), destPage.resources, dest)
                            try {
                                val xobj = srcPage.copyAsFormXObject(dest)
                                canvas.saveState()
                                canvas.concatMatrix(scaleX.toDouble(), 0.0, 0.0, scaleY.toDouble(), offX.toDouble(), offY.toDouble())
                                canvas.addXObjectAt(xobj, 0f, 0f)
                                canvas.restoreState()
                            } finally { canvas.release() }
                        }
                    }
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun mirrorPages(sourceUri: Uri, pages: List<Int>, horizontal: Boolean): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("mirrored")
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val targets = if (pages.isEmpty()) (1..src.numberOfPages).toSet() else pages.toSet()
                    val merger = PdfMerger(dest)
                    for (i in 1..src.numberOfPages) {
                        if (i !in targets) {
                            merger.merge(src, i, i)
                            continue
                        }
                        val srcPage = src.getPage(i)
                        val ps = srcPage.pageSize
                        val destPage = dest.addNewPage(PageSize(ps.width, ps.height))
                        val canvas = PdfCanvas(destPage.newContentStreamAfter(), destPage.resources, dest)
                        try {
                            val xobj = srcPage.copyAsFormXObject(dest)
                            canvas.saveState()
                            if (horizontal) {
                                canvas.concatMatrix(-1.0, 0.0, 0.0, 1.0, ps.width.toDouble(), 0.0)
                            } else {
                                canvas.concatMatrix(1.0, 0.0, 0.0, -1.0, 0.0, ps.height.toDouble())
                            }
                            canvas.addXObjectAt(xobj, 0f, 0f)
                            canvas.restoreState()
                        } finally { canvas.release() }
                    }
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun insertBlankPage(sourceUri: Uri, position: Int, width: Float, height: Float): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("inserted")
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val insertAt = position.coerceIn(1, src.numberOfPages + 1)
                    val merger = PdfMerger(dest)
                    if (insertAt > 1) merger.merge(src, 1, insertAt - 1)
                    dest.addNewPage(PageSize(width, height))
                    if (insertAt <= src.numberOfPages) merger.merge(src, insertAt, src.numberOfPages)
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun splitBySize(sourceUri: Uri, maxSizeMb: Int, outputPrefix: String): AppResult<List<Uri>> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val totalBytes = input.length().coerceAtLeast(1L)
                val probe = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val pageCount = probe.numberOfPages
                probe.close()

                val maxBytes = maxSizeMb.coerceAtLeast(1) * 1024L * 1024L
                val avgBytesPerPage = (totalBytes / pageCount.coerceAtLeast(1)).coerceAtLeast(1L)
                val pagesPerChunk = (maxBytes / avgBytesPerPage).toInt().coerceAtLeast(1)

                val outputs = mutableListOf<File>()
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                try {
                    var pageStart = 1
                    var partIndex = 1
                    while (pageStart <= pageCount) {
                        val pageEnd = (pageStart + pagesPerChunk - 1).coerceAtMost(pageCount)
                        val out = createOutputFile("${outputPrefix}_part$partIndex")
                        val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(out.absolutePath))
                        try { PdfMerger(dest).merge(src, pageStart, pageEnd) } finally { dest.close() }
                        outputs.add(out)
                        pageStart = pageEnd + 1
                        partIndex++
                    }
                } finally { src.close() }
                outputs.map { Uri.fromFile(it) }
            }.toAppResult()
        }

    override suspend fun splitByBookmark(sourceUri: Uri, outputPrefix: String): AppResult<List<Uri>> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val pageCount = src.numberOfPages
                // Approximate: split into as many roughly-equal chunks as there are top-level
                // bookmarks. Resolving each bookmark's exact destination page requires deeper
                // outline-destination APIs; this keeps the split reliable while still being
                // informed by the document's actual bookmark count.
                val bookmarkCount = try {
                    src.getOutlines(false)?.allChildren?.size ?: 0
                } catch (_: Exception) {
                    0
                }
                val chunks = bookmarkCount.coerceAtLeast(1)
                val perChunk = ((pageCount + chunks - 1) / chunks).coerceAtLeast(1)

                val outputs = mutableListOf<File>()
                try {
                    var pageStart = 1
                    var partIndex = 1
                    while (pageStart <= pageCount) {
                        val pageEnd = (pageStart + perChunk - 1).coerceAtMost(pageCount)
                        val out = createOutputFile("${outputPrefix}_part$partIndex")
                        val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(out.absolutePath))
                        try { PdfMerger(dest).merge(src, pageStart, pageEnd) } finally { dest.close() }
                        outputs.add(out)
                        pageStart = pageEnd + 1
                        partIndex++
                    }
                } finally { src.close() }
                outputs.map { Uri.fromFile(it) }
            }.toAppResult()
        }

    override suspend fun splitEveryNPages(sourceUri: Uri, n: Int, outputPrefix: String): AppResult<List<Uri>> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val pageCount = src.numberOfPages
                val step = n.coerceAtLeast(1)
                val outputs = mutableListOf<File>()
                try {
                    var pageStart = 1
                    var partIndex = 1
                    while (pageStart <= pageCount) {
                        val pageEnd = (pageStart + step - 1).coerceAtMost(pageCount)
                        val out = createOutputFile("${outputPrefix}_part$partIndex")
                        val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(out.absolutePath))
                        try { PdfMerger(dest).merge(src, pageStart, pageEnd) } finally { dest.close() }
                        outputs.add(out)
                        pageStart = pageEnd + 1
                        partIndex++
                    }
                } finally { src.close() }
                outputs.map { Uri.fromFile(it) }
            }.toAppResult()
        }

    override suspend fun addPageNumbers(sourceUri: Uri, config: PageNumberConfig): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return addPageNumbers(input, createOutputFile("numbered"), config).toUriResult()
    }

    override suspend fun addHeaderFooter(sourceUri: Uri, config: HeaderFooterConfig): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return addHeaderFooter(input, createOutputFile("headerfooter"), config).toUriResult()
    }

    override suspend fun addWatermark(sourceUri: Uri, config: WatermarkConfig): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return addWatermark(input, createOutputFile("watermarked"), config).toUriResult()
    }

    override suspend fun addBackground(sourceUri: Uri, config: BackgroundConfig): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val input = resolveUriToFile(sourceUri)
                val output = createOutputFile("background")
                val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath), PdfWriter(output.absolutePath))
                try {
                    val targets = if (config.pages.isEmpty()) (1..doc.numberOfPages).toList() else config.pages
                    targets.forEach { n ->
                        if (n in 1..doc.numberOfPages) {
                            val page = doc.getPage(n)
                            val ps = page.pageSize
                            val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, doc)
                            try {
                                canvas.saveState()
                                val gs = PdfExtGState().apply { fillOpacity = config.opacity }
                                canvas.setExtGState(gs)
                                val imageUri = config.imageUri
                                val imageFile = imageUri?.path?.let { File(it) }
                                if (imageFile != null && imageFile.exists()) {
                                    val imgData = ImageDataFactory.create(imageFile.absolutePath)
                                    canvas.addXObjectWithTransformationMatrix(
                                        com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                                        ps.width, 0f, 0f, ps.height, ps.left, ps.bottom
                                    )
                                } else {
                                    val r = android.graphics.Color.red(config.color) / 255f
                                    val g = android.graphics.Color.green(config.color) / 255f
                                    val b = android.graphics.Color.blue(config.color) / 255f
                                    canvas.setFillColor(DeviceRgb(r, g, b))
                                    canvas.rectangle(ps.left.toDouble(), ps.bottom.toDouble(), ps.width.toDouble(), ps.height.toDouble())
                                    canvas.fill()
                                }
                                canvas.restoreState()
                            } finally { canvas.release() }
                        }
                    }
                } finally { doc.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }

    override suspend fun compressPdf(sourceUri: Uri, config: CompressConfig): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return compress(input, createOutputFile("compressed"), config).toUriResult()
    }

    override suspend fun optimizePdf(sourceUri: Uri, aggressive: Boolean): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        val config = CompressConfig(
            level = if (aggressive) 9 else 6,
            targetDpi = if (aggressive) 100 else 150
        )
        return compress(input, createOutputFile("optimized"), config).toUriResult()
    }

    // ===================== URI-BASED TOOL-SCREEN OPERATIONS =====================

    override suspend fun insertImagePage(sourceUri: Uri, position: Int, config: ImageInsertionConfig): AppResult<Uri> {
        val input = runCatching { resolveUriToFile(sourceUri) }
            .getOrElse { return AppResult.Error(it.toAppException()) }
        return withContext(dispatchers.io) {
            runCatching {
                val output = createOutputFile("with_image")
                val src = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(input.absolutePath))
                val dest = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    val insertAt = position.coerceIn(1, src.numberOfPages + 1)
                    val merger = PdfMerger(dest)
                    if (insertAt > 1) merger.merge(src, 1, insertAt - 1)

                    val imageFile = File(config.imageUri.path ?: throw PdfProcessingError.ProcessingFailed("Invalid image URI"))
                    val imgData = ImageDataFactory.create(imageFile.absolutePath)
                    val imgW = imgData.width.toFloat()
                    val imgH = imgData.height.toFloat()
                    val availW = (config.pageWidth - 2 * config.margin).coerceAtLeast(1f)
                    val availH = (config.pageHeight - 2 * config.margin).coerceAtLeast(1f)
                    val (drawW, drawH) = when (config.fitMode) {
                        ImageFitMode.FILL -> availW to availH
                        ImageFitMode.FIT_WIDTH -> availW to (availW * imgH / imgW)
                        ImageFitMode.FIT_HEIGHT -> (availH * imgW / imgH) to availH
                        ImageFitMode.ORIGINAL -> imgW to imgH
                        ImageFitMode.FIT_CENTER -> {
                            val scale = kotlin.math.min(availW / imgW, availH / imgH)
                            (imgW * scale) to (imgH * scale)
                        }
                    }
                    val newPage = dest.addNewPage(PageSize(config.pageWidth, config.pageHeight))
                    val canvas = PdfCanvas(newPage.newContentStreamAfter(), newPage.resources, dest)
                    try {
                        val x = (config.pageWidth - drawW) / 2f
                        val y = (config.pageHeight - drawH) / 2f
                        canvas.saveState()
                        canvas.addXObjectWithTransformationMatrix(
                            com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                            drawW, 0f, 0f, drawH, x, y
                        )
                        canvas.restoreState()
                    } finally { canvas.release() }

                    if (insertAt <= src.numberOfPages) merger.merge(src, insertAt, src.numberOfPages)
                } finally { dest.close(); src.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }
    }

    override suspend fun combineImagesToPdf(images: List<Uri>, outputName: String, config: ImageInsertionConfig): AppResult<Uri> =
        withContext(dispatchers.io) {
            runCatching {
                val output = createOutputFile(outputName.ifBlank { "combined" })
                val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(output.absolutePath))
                try {
                    images.forEach { imageUri ->
                        val imageFile = imageUri.path?.let { File(it) }
                            ?: throw PdfProcessingError.ProcessingFailed("Invalid image URI")
                        val imgData = ImageDataFactory.create(imageFile.absolutePath)
                        val imgW = imgData.width.toFloat()
                        val imgH = imgData.height.toFloat()
                        val availW = (config.pageWidth - 2 * config.margin).coerceAtLeast(1f)
                        val availH = (config.pageHeight - 2 * config.margin).coerceAtLeast(1f)
                        val (drawW, drawH) = when (config.fitMode) {
                            ImageFitMode.FILL -> availW to availH
                            ImageFitMode.FIT_WIDTH -> availW to (availW * imgH / imgW)
                            ImageFitMode.FIT_HEIGHT -> (availH * imgW / imgH) to availH
                            ImageFitMode.ORIGINAL -> imgW to imgH
                            ImageFitMode.FIT_CENTER -> {
                                val scale = kotlin.math.min(availW / imgW, availH / imgH)
                                (imgW * scale) to (imgH * scale)
                            }
                        }
                        val page = doc.addNewPage(PageSize(config.pageWidth, config.pageHeight))
                        val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, doc)
                        try {
                            val x = (config.pageWidth - drawW) / 2f
                            val y = (config.pageHeight - drawH) / 2f
                            canvas.saveState()
                            canvas.addXObjectWithTransformationMatrix(
                                com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                                drawW, 0f, 0f, drawH, x, y
                            )
                            canvas.restoreState()
                        } finally { canvas.release() }
                    }
                } finally { doc.close() }
                Uri.fromFile(output)
            }.toAppResult()
        }


    // ===================== PRIVATE HELPERS =====================

    /**
     * Resolves any Uri (file:// or content://, including providers that block direct
     * ParcelFileDescriptor access) to a local File PDFBox/iText can read.
     */
    private fun resolveUriToFile(uri: Uri): File {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val f = File(path)
                if (f.exists() && f.canRead()) return f
            }
        }
        val tmp = File(context.cacheDir, "pdf_op_src_${System.nanoTime()}.pdf")
        val opened = context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (!opened || !tmp.exists() || tmp.length() == 0L) {
            throw PdfProcessingError.ProcessingFailed("Cannot open source file")
        }
        return tmp
    }

    private fun createOutputFile(prefix: String, extension: String = "pdf"): File =
        File(context.cacheDir, "${prefix}_${System.nanoTime()}.$extension")

    private fun toPoints(value: Float, unit: MeasurementUnit): Float = when (unit) {
        MeasurementUnit.POINT -> value
        MeasurementUnit.INCH -> value * 72f
        MeasurementUnit.CM -> value * 28.3465f
        MeasurementUnit.MM -> value * 2.83465f
    }

    // ===================== PRIVATE HELPERS (existing) =====================
    private fun embedTextAsBitmap(
        canvas: PdfCanvas, doc: com.itextpdf.kernel.pdf.PdfDocument, page: com.itextpdf.kernel.pdf.PdfPage,
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
