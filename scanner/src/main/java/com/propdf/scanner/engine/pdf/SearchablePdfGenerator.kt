package com.propdf.scanner.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import com.propdf.core.pdf.text.InvisibleTextLayer
import com.propdf.core.pdf.text.UnicodeFontResolver
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates PDFs from scanned images using Android's built-in PdfDocument API.
 * No external PDF library dependency required in scanner module.
 */
@Singleton
class SearchablePdfGenerator @Inject constructor(@ApplicationContext private val context: Context) {

    private val ocrEngine = MlKitOcrEngine(context)

    /** Outcome of a searchable-PDF run. [charactersDropped] counts OCR characters no available font could encode. */
    data class SearchableResult(val uri: Uri, val linesWithText: Int, val charactersDropped: Int)

    /**
     * Generate a PDF from scanned images with an invisible, extractable OCR text layer.
     *
     * Phase 1 root cause of the empty text layer: the previous version drew the OCR text on Android's
     * PdfDocument canvas with a zero-alpha paint. Skia's PDF backend does not reliably emit glyphs for a fully
     * transparent paint, and where it does the text is not guaranteed to carry a ToUnicode map, so extraction
     * returned nothing. (Confirmed by source reading only; not reproduced at runtime in the authoring environment.)
     * Now the page images are written with PdfDocument as before and the text layer is added afterwards with PDFBox
     * as render-mode-3 text via [InvisibleTextLayer], the same approach as the batch OCR path.
     *
     * Fails (rather than reporting success) when no page produced any recognised text.
     */
    suspend fun generateSearchablePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        _quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = generateSearchablePdfDetailed(images, outputFileName, onProgress).map { it.uri }

    suspend fun generateSearchablePdfDetailed(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<SearchableResult> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No pages to export"))
        PDFBoxResourceLoader.init(context)
        val imageOnly = File.createTempFile("scan_img_", ".pdf", context.cacheDir)
        val withText = File.createTempFile("scan_txt_", ".pdf", context.cacheDir)
        try {
            val pageWidth = 595 // A4 width in points (72 dpi)
            val pageHeight = 842 // A4 height in points
            val pageLines = ArrayList<List<InvisibleTextLayer.Line>>()
            var ocrFailures = 0

            val document = PdfDocument()
            try {
                images.forEachIndexed { index, bitmap ->
                    coroutineContext.ensureActive()
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = document.startPage(pageInfo)
                    val scale = minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
                    val imgW = bitmap.width * scale
                    val imgH = bitmap.height * scale
                    val x = (pageWidth - imgW) / 2
                    val y = (pageHeight - imgH) / 2
                    page.canvas.drawBitmap(bitmap, null, Rect(x.toInt(), y.toInt(), (x + imgW).toInt(), (y + imgH).toInt()), null)
                    document.finishPage(page)

                    // Map OCR boxes from bitmap pixels to page points (the image sits inside the page, scaled).
                    val ocr = ocrEngine.recognizeText(bitmap)
                    if (ocr.isFailure) ocrFailures++
                    pageLines.add(
                        ocr.getOrNull()?.blocks.orEmpty().flatMap { it.lines }.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            if (line.text.isBlank()) return@mapNotNull null
                            InvisibleTextLayer.Line(
                                line.text,
                                (x + box.left * scale).toInt(), (y + box.top * scale).toInt(),
                                (x + box.right * scale).toInt(), (y + box.bottom * scale).toInt()
                            )
                        }
                    )
                    onProgress(index + 1, images.size)
                }
                FileOutputStream(imageOnly).use { document.writeTo(it) }
            } finally {
                document.close()
            }

            if (ocrFailures == images.size) {
                return@withContext Result.failure(IOException("Text recognition failed for every page; no searchable PDF was created"))
            }
            if (pageLines.all { it.isEmpty() }) {
                return@withContext Result.failure(IOException("No text was recognised on any page, so a searchable PDF would not be searchable. Export as an image PDF instead."))
            }

            var drawn = 0
            var dropped = 0
            PDDocument.load(imageOnly, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                val resolver = UnicodeFontResolver(pdf)
                pageLines.forEachIndexed { i, lines ->
                    coroutineContext.ensureActive()
                    val stats = InvisibleTextLayer.draw(
                        pdf, pdf.getPage(i), lines, pageWidth, pageHeight,
                        pageWidth.toFloat(), pageHeight.toFloat(), floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), resolver
                    )
                    drawn += stats.linesDrawn
                }
                dropped = resolver.charactersDropped
                pdf.save(withText)
            }
            if (drawn == 0) {
                return@withContext Result.failure(IOException("Recognised text could not be written to the PDF (no usable font for these characters)"))
            }

            // Validate before publishing: reopen, same page count, and some text really extractable.
            PDDocument.load(withText).use { check ->
                if (check.numberOfPages != images.size) throw IOException("Output validation failed: page count differs")
                val extracted = PDFTextStripper().getText(check)
                if (extracted.isBlank()) throw IOException("Output validation failed: text layer is empty")
            }

            coroutineContext.ensureActive()
            val uri = publish(withText, outputFileName) ?: return@withContext Result.failure(IOException("Failed to save PDF"))
            Result.success(SearchableResult(uri, drawn, dropped))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            imageOnly.delete()
            withText.delete()
        }
    }

    /** Copies [source] into Downloads/ProPDF; a half-written MediaStore entry is deleted on failure. */
    private fun publish(source: File, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open output")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
                val done = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                uri
            } catch (e: Exception) {
                try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").apply { mkdirs() }
            val file = File(dir, fileName)
            val part = File(dir, "$fileName.part")
            try {
                source.copyTo(part, overwrite = true)
                if (!part.renameTo(file)) { part.copyTo(file, overwrite = true) }
                Uri.fromFile(file)
            } finally {
                part.delete()
            }
        }
    }

    /**
     * Generate plain image-only PDF (no OCR layer).
     */
    suspend fun generateImagePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        _quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842

            images.forEachIndexed { index, bitmap ->
                if (!isActive) {
                    return@withContext Result.failure(Exception("Cancelled"))
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                val scale = minOf(pageWidth.toFloat() / bitmap.width, pageHeight.toFloat() / bitmap.height)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val x = (pageWidth - imgW) / 2
                val y = (pageHeight - imgH) / 2

                canvas.drawBitmap(bitmap, null, Rect(x.toInt(), y.toInt(), (x + imgW).toInt(), (y + imgH).toInt()), null)
                document.finishPage(page)
                onProgress(index + 1, images.size)
            }

            val uri = saveDocument(document, outputFileName)

            if (uri != null) Result.success(uri) else Result.failure(Exception("Failed to save PDF"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document.close()
        }
    }

    private fun saveDocument(document: PdfDocument, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    document.writeTo(out)
                }
            }
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { document.writeTo(it) }
            Uri.fromFile(file)
        }
    }
}
