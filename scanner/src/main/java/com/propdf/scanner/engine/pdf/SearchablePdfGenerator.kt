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
import kotlinx.coroutines.isActive
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

    /**
     * Generate a PDF from scanned images.
     * OCR text overlay is a placeholder - full searchable PDF requires PDFBox in viewer module.
     */
    suspend fun generateSearchablePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val document = PdfDocument()
            val pageWidth = 595  // A4 width in points (72 dpi)
            val pageHeight = 842 // A4 height in points

            images.forEachIndexed { index, bitmap ->
                if (!isActive) {
                    document.close()
                    return@withContext Result.failure(Exception("Cancelled"))
                }

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                // Scale image to fit page
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
            document.close()

            if (uri != null) {
                Result.success(uri)
            } else {
                Result.failure(Exception("Failed to save PDF"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate plain image-only PDF (no OCR layer).
     */
    suspend fun generateImagePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val document = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842

            images.forEachIndexed { index, bitmap ->
                if (!isActive) {
                    document.close()
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
            document.close()

            if (uri != null) Result.success(uri) else Result.failure(Exception("Failed to save PDF"))
        } catch (e: Exception) {
            Result.failure(e)
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
