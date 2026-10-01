package com.propdf.scanner.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import com.propdf.scanner.model.ColorFilter
import com.propdf.scanner.model.ExportConfig
import com.propdf.scanner.model.PageSize
import com.propdf.scanner.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Creates new scan PDFs with the Android platform writer; no third-party PDF engine is needed. */
class PdfCreator(private val context: Context) {
    private val imageEnhancer = ImageEnhancer()

    suspend fun createPdf(pages: List<ScannedPage>, outputFile: File, config: ExportConfig): String =
        withContext(Dispatchers.IO) {
            PdfDocument().use { document ->
                pages.forEachIndexed { index, page ->
                    val path = page.processedImagePath ?: page.originalImagePath
                    val bitmap = BitmapFactory.decodeFile(path)
                        ?: throw IllegalStateException("Failed to decode: $path")
                    val processed = if (config.colorMode != ColorFilter.ORIGINAL) {
                        imageEnhancer.applyFilter(bitmap, config.colorMode)
                    } else bitmap
                    try {
                        val size = getPageSize(config.pageSize, processed)
                        val pageInfo = PdfDocument.PageInfo.Builder(size.first, size.second, index + 1).create()
                        val pdfPage = document.startPage(pageInfo)
                        val scale = minOf(size.first.toFloat() / processed.width, size.second.toFloat() / processed.height)
                        val width = (processed.width * scale).toInt()
                        val height = (processed.height * scale).toInt()
                        val left = (size.first - width) / 2
                        val top = (size.second - height) / 2
                        pdfPage.canvas.drawBitmap(processed, null, android.graphics.Rect(left, top, left + width, top + height), null)
                        document.finishPage(pdfPage)
                    } finally {
                        if (processed !== bitmap) processed.recycle()
                        bitmap.recycle()
                    }
                }
                FileOutputStream(outputFile).use(document::writeTo)
            }
            outputFile.absolutePath
        }

    suspend fun exportImages(pages: List<ScannedPage>, outputDir: File, config: ExportConfig): List<String> = withContext(Dispatchers.IO) {
        pages.mapIndexedNotNull { index, page ->
            val bitmap = BitmapFactory.decodeFile(page.processedImagePath ?: page.originalImagePath) ?: return@mapIndexedNotNull null
            val processed = if (config.colorMode != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(bitmap, config.colorMode) else bitmap
            try {
                val format = if (config.format == com.propdf.scanner.model.ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
                File(outputDir, "page_${index + 1}.$extension").also { output ->
                    FileOutputStream(output).use { processed.compress(format, config.quality, it) }
                }.absolutePath
            } finally {
                if (processed !== bitmap) processed.recycle()
                bitmap.recycle()
            }
        }
    }

    private fun getPageSize(pageSize: PageSize, bitmap: Bitmap): Pair<Int, Int> = when (pageSize) {
        PageSize.A4 -> 595 to 842
        PageSize.LETTER -> 612 to 792
        PageSize.LEGAL -> 612 to 1008
        PageSize.AUTO -> if (bitmap.width > bitmap.height) 842 to 595 else 595 to 842
    }
}
