package com.propdf.scanner.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.ocr.OcrResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SearchablePdfGenerator(private val context: Context) {

    private val ocrEngine = MlKitOcrEngine(context)

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun generateSearchablePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val doc = PDDocument()
            val pageWidth = PDRectangle.A4.width
            val pageHeight = PDRectangle.A4.height

            images.forEachIndexed { index, bitmap ->
                if (!isActive) {
                    doc.close()
                    ocrEngine.close()
                    return@withContext Result.failure(Exception("Cancelled"))
                }

                val ocrResult = ocrEngine.recognizeText(bitmap).getOrNull()
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                val pdImage = JPEGFactory.createFromImage(doc, bitmap, quality / 100f)
                val contentStream = PDPageContentStream(doc, page)

                val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val x = (pageWidth - imgW) / 2
                val y = (pageHeight - imgH) / 2

                contentStream.drawImage(pdImage, x, y, imgW, imgH)

                if (ocrResult != null) {
                    overlayOcrText(contentStream, ocrResult, x, y, imgW, imgH, bitmap.width, bitmap.height)
                }

                contentStream.close()
                onProgress(index + 1, images.size)
            }

            val uri = saveDocument(doc, outputFileName)
            doc.close()
            ocrEngine.close()

            if (uri != null) {
                Result.success(uri)
            } else {
                Result.failure(Exception("Failed to save PDF"))
            }
        } catch (e: Exception) {
            ocrEngine.close()
            Result.failure(e)
        }
    }

    suspend fun generateImagePdf(
        images: List<Bitmap>,
        outputFileName: String = "Scan_${System.currentTimeMillis()}.pdf",
        quality: Int = 85,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val doc = PDDocument()
            val pageWidth = PDRectangle.A4.width
            val pageHeight = PDRectangle.A4.height

            images.forEachIndexed { index, bitmap ->
                if (!isActive) {
                    doc.close()
                    return@withContext Result.failure(Exception("Cancelled"))
                }

                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                val pdImage = JPEGFactory.createFromImage(doc, bitmap, quality / 100f)
                val contentStream = PDPageContentStream(doc, page)

                val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val x = (pageWidth - imgW) / 2
                val y = (pageHeight - imgH) / 2

                contentStream.drawImage(pdImage, x, y, imgW, imgH)
                contentStream.close()
                onProgress(index + 1, images.size)
            }

            val uri = saveDocument(doc, outputFileName)
            doc.close()

            if (uri != null) Result.success(uri) else Result.failure(Exception("Failed to save PDF"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun overlayOcrText(
        contentStream: PDPageContentStream,
        ocrResult: OcrResult,
        imgX: Float,
        imgY: Float,
        imgW: Float,
        imgH: Float,
        origWidth: Int,
        origHeight: Int
    ) {
        contentStream.setFont(PDType1Font.HELVETICA, 8f)
        contentStream.setNonStrokingColor(0f, 0f, 0f)

        ocrResult.blocks.forEach { block ->
            block.lines.forEach { line ->
                line.words.forEach { word ->
                    val box = word.boundingBox ?: return@forEach
                    val pdfX = imgX + (box.left / origWidth.toFloat()) * imgW
                    val pdfY = imgY + imgH - ((box.bottom / origHeight.toFloat()) * imgH)

                    contentStream.beginText()
                    contentStream.newLineAtOffset(pdfX, pdfY)
                    contentStream.showText(word.text)
                    contentStream.endText()
                }
            }
        }
    }

    private fun saveDocument(doc: PDDocument, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    doc.save(out)
                }
            }
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { doc.save(it) }
            Uri.fromFile(file)
        }
    }
}
