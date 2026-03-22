package com.propdf.editor.data.repository

import android.content.Context
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class PdfOperationsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun mergePdfs(inputFiles: List<File>, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writer = PdfWriter(outputFile.absolutePath)
            val outputPdf = PdfDocument(writer)
            val merger = PdfMerger(outputPdf)
            for (file in inputFiles) {
                if (!file.exists()) continue
                val reader = PdfReader(file.absolutePath)
                val src = PdfDocument(reader)
                merger.merge(src, 1, src.numberOfPages)
                src.close()
            }
            outputPdf.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun splitPdf(inputFile: File, outputDir: File, ranges: List<IntRange>): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile.absolutePath)
            val sourcePdf = PdfDocument(reader)
            val outputFiles = mutableListOf<File>()
            ranges.forEachIndexed { index, range ->
                val outFile = File(outputDir, "${inputFile.nameWithoutExtension}_part${index + 1}.pdf")
                val writer = PdfWriter(outFile.absolutePath)
                val destPdf = PdfDocument(writer)
                val merger = PdfMerger(destPdf)
                val from = range.first.coerceIn(1, sourcePdf.numberOfPages)
                val to = range.last.coerceIn(from, sourcePdf.numberOfPages)
                merger.merge(sourcePdf, from, to)
                destPdf.close()
                outputFiles.add(outFile)
            }
            sourcePdf.close()
            Result.success(outputFiles)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun compressPdf(inputFile: File, outputFile: File, quality: Int = 1): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writerProps = WriterProperties().apply {
                setFullCompressionMode(true)
                useSmartMode()
            }
            val reader = PdfReader(inputFile.absolutePath)
            val writer = PdfWriter(outputFile.absolutePath, writerProps)
            PdfDocument(reader, writer).close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun encryptPdf(inputFile: File, outputFile: File, userPassword: String?,
                            ownerPassword: String, allowPrinting: Boolean = true,
                            allowCopying: Boolean = false): Result<File> = withContext(Dispatchers.IO) {
        try {
            var permissions = EncryptionConstants.ALLOW_SCREENREADERS
            if (allowPrinting) permissions = permissions or EncryptionConstants.ALLOW_PRINTING
            if (allowCopying) permissions = permissions or EncryptionConstants.ALLOW_COPY
            val writerProps = WriterProperties().setStandardEncryption(
                userPassword?.toByteArray(), ownerPassword.toByteArray(),
                permissions, EncryptionConstants.ENCRYPTION_AES_256
            )
            PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, writerProps)).close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun removePdfPassword(inputFile: File, outputFile: File, password: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val readerProps = ReaderProperties().setPassword(password.toByteArray())
            PdfDocument(PdfReader(inputFile.absolutePath, readerProps), PdfWriter(outputFile.absolutePath)).close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addTextWatermark(inputFile: File, outputFile: File, text: String,
                                  opacity: Float = 0.3f, rotation: Float = 45f): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile.absolutePath)
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(reader, writer)
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                val pageSize = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, pdfDoc)
                val gs = PdfExtGState(); gs.fillOpacity = opacity
                canvas.saveState().setExtGState(gs).beginText()
                    .setFontAndSize(font, 60f).setFillColor(ColorConstants.LIGHT_GRAY)
                val x = (pageSize.left + pageSize.right) / 2
                val y = (pageSize.bottom + pageSize.top) / 2
                canvas.setTextMatrix(
                    cos(Math.toRadians(rotation.toDouble())).toFloat(),
                    sin(Math.toRadians(rotation.toDouble())).toFloat(),
                    -sin(Math.toRadians(rotation.toDouble())).toFloat(),
                    cos(Math.toRadians(rotation.toDouble())).toFloat(), x, y
                ).showText(text).endText().restoreState()
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deletePages(inputFile: File, outputFile: File, pagesToDelete: List<Int>): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile.absolutePath)
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(reader, writer)
            pagesToDelete.sortedDescending().forEach { pageNum ->
                if (pageNum in 1..pdfDoc.numberOfPages) pdfDoc.removePage(pageNum)
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun rotatePages(inputFile: File, outputFile: File, pages: Map<Int, Int>): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile.absolutePath)
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(reader, writer)
            pages.forEach { (pageNum, degrees) ->
                if (pageNum in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(pageNum)
                    page.put(PdfName.Rotate, PdfNumber((page.rotation + degrees) % 360))
                }
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc)
            for (imgFile in imageFiles) {
                val imgData = ImageDataFactory.create(imgFile.absolutePath)
                val img = Image(imgData).setAutoScale(true)
                pdfDoc.addNewPage()
                document.add(img)
            }
            document.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }
}
