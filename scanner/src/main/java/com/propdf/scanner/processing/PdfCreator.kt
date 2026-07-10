package com.propdf.scanner.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.itextpdf.kernel.geom.PageSize as ItextPageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import com.propdf.scanner.model.ColorFilter
import com.propdf.scanner.model.ExportConfig
import com.propdf.scanner.model.PageSize
import com.propdf.scanner.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PdfCreator(private val context: Context) {
    private val imageEnhancer = ImageEnhancer()

    suspend fun createPdf(pages: List<ScannedPage>, outputFile: File, config: ExportConfig): String = withContext(Dispatchers.IO) {
        val writerProperties = WriterProperties().setFullCompressionMode(true).setCompressionLevel(9)
        val pdfWriter = PdfWriter(FileOutputStream(outputFile), writerProperties)
        val pdfDocument = PdfDocument(pdfWriter)
        val document = Document(pdfDocument)

        try {
            pages.forEachIndexed { _, page ->
                val imagePath = page.processedImagePath ?: page.originalImagePath
                val bitmap = BitmapFactory.decodeFile(imagePath) ?: throw IllegalStateException("Failed to decode: $imagePath")
                val processedBitmap = if (config.colorMode != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(bitmap, config.colorMode) else bitmap

                val pageSize = getPageSize(config.pageSize, processedBitmap)
                pdfDocument.addNewPage(pageSize)

                val imageData = bitmapToImageData(processedBitmap, config)
                val image = Image(imageData)
                image.setAutoScale(true)
                document.add(image)

                if (config.colorMode != ColorFilter.ORIGINAL) processedBitmap.recycle()
                if (processedBitmap != bitmap) bitmap.recycle()
            }
        } finally {
            document.close()
            pdfDocument.close()
        }
        outputFile.absolutePath
    }

    suspend fun exportImages(pages: List<ScannedPage>, outputDir: File, config: ExportConfig): List<String> = withContext(Dispatchers.IO) {
        val exportedPaths = mutableListOf<String>()
        pages.forEachIndexed { index, page ->
            val imagePath = page.processedImagePath ?: page.originalImagePath
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return@forEachIndexed
            val processedBitmap = if (config.colorMode != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(bitmap, config.colorMode) else bitmap

            val format = when (config.format) {
                com.propdf.scanner.model.ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                com.propdf.scanner.model.ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }
            val extension = when (config.format) {
                com.propdf.scanner.model.ExportFormat.JPEG -> "jpg"
                com.propdf.scanner.model.ExportFormat.PNG -> "png"
                else -> "jpg"
            }

            val outputFile = File(outputDir, "page_${index + 1}.$extension")
            FileOutputStream(outputFile).use { out -> processedBitmap.compress(format, config.quality, out) }
            exportedPaths.add(outputFile.absolutePath)

            if (config.colorMode != ColorFilter.ORIGINAL) processedBitmap.recycle()
            if (processedBitmap != bitmap) bitmap.recycle()
        }
        exportedPaths
    }

    private fun getPageSize(pageSize: PageSize, bitmap: Bitmap): ItextPageSize = when (pageSize) {
        PageSize.A4 -> ItextPageSize.A4
        PageSize.LETTER -> ItextPageSize.LETTER
        PageSize.LEGAL -> ItextPageSize.LEGAL
        PageSize.AUTO -> if (bitmap.width.toFloat() / bitmap.height > 1.0) ItextPageSize.A4.rotate() else ItextPageSize.A4
    }

    private fun bitmapToImageData(bitmap: Bitmap, config: ExportConfig): com.itextpdf.kernel.pdf.xobject.PdfImageXObject {
        val stream = ByteArrayOutputStream()
        val format = if (config.quality == 100) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        bitmap.compress(format, config.quality, stream)
        return com.itextpdf.kernel.pdf.xobject.PdfImageXObject(ImageDataFactory.create(stream.toByteArray()))
    }
}
