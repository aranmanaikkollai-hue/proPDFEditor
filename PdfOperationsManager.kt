package com.propdf.editor.data.repository

import android.content.Context
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.kernel.colors.ColorConstants
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
                val src = PdfDocument(PdfReader(file.absolutePath))
                merger.merge(src, 1, src.numberOfPages)
                src.close()
            }
            outputPdf.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun splitPdf(inputFile: File, outputDir: File, ranges: List<IntRange>): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val src = PdfDocument(PdfReader(inputFile.absolutePath))
            val outputFiles = mutableListOf<File>()
            ranges.forEachIndexed { i, range ->
                val out = File(outputDir, "${inputFile.nameWithoutExtension}_part${i + 1}.pdf")
                val dest = PdfDocument(PdfWriter(out.absolutePath))
                PdfMerger(dest).merge(src, range.first.coerceIn(1, src.numberOfPages), range.last.coerceIn(1, src.numberOfPages))
                dest.close()
                outputFiles.add(out)
            }
            src.close()
            Result.success(outputFiles)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun compressPdf(inputFile: File, outputFile: File, quality: Int = 1): Result<File> = withContext(Dispatchers.IO) {
        try {
            val props = WriterProperties().apply { setFullCompressionMode(true); useSmartMode() }
            PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, props)).close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun encryptPdf(inputFile: File, outputFile: File, userPassword: String?,
        ownerPassword: String, allowPrinting: Boolean = true, allowCopying: Boolean = false): Result<File> = withContext(Dispatchers.IO) {
        try {
            var perms = EncryptionConstants.ALLOW_SCREENREADERS
            if (allowPrinting) perms = perms or EncryptionConstants.ALLOW_PRINTING
            if (allowCopying) perms = perms or EncryptionConstants.ALLOW_COPY
            val props = WriterProperties().setStandardEncryption(
                userPassword?.toByteArray(), ownerPassword.toByteArray(),
                perms, EncryptionConstants.ENCRYPTION_AES_256
            )
            PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, props)).close()
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
            val pdfDoc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                val ps = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, pdfDoc)
                val gs = PdfExtGState(); gs.fillOpacity = opacity
                canvas.saveState().setExtGState(gs).beginText()
                    .setFontAndSize(font, 60f).setFillColor(ColorConstants.LIGHT_GRAY)
                val x = (ps.left + ps.right) / 2; val y = (ps.bottom + ps.top) / 2
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
            val pdfDoc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            pagesToDelete.sortedDescending().forEach { if (it in 1..pdfDoc.numberOfPages) pdfDoc.removePage(it) }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun rotatePages(inputFile: File, outputFile: File, pages: Map<Int, Int>): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDoc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            pages.forEach { (num, deg) ->
                if (num in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(num)
                    page.put(PdfName.Rotate, PdfNumber((page.rotation + deg) % 360))
                }
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addPageNumbers(inputFile: File, outputFile: File,
        format: String = "Page %d of %d"): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDoc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            val total = pdfDoc.numberOfPages
            for (i in 1..total) {
                val page = pdfDoc.getPage(i)
                val ps = page.pageSize
                var text = format.replace("%d", "$i")
                if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                canvas.beginText().setFontAndSize(font, 10f)
                    .moveText(((ps.left + ps.right) / 2).toDouble(), (ps.bottom + 15f).toDouble())
                    .showText(text).endText()
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun addHeaderFooter(inputFile: File, outputFile: File,
        headerText: String? = null, footerText: String? = null): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDoc = PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                val ps = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                canvas.beginText().setFontAndSize(font, 10f)
                headerText?.let {
                    canvas.moveText(((ps.left + ps.right) / 2).toDouble(), (ps.top - 20f).toDouble())
                        .showText(it)
                }
                footerText?.let {
                    canvas.moveText(((ps.left + ps.right) / 2).toDouble(), (ps.bottom + 10f).toDouble())
                        .showText(it)
                }
                canvas.endText()
            }
            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDoc = PdfDocument(PdfWriter(outputFile.absolutePath))
            val document = Document(pdfDoc)
            for (imgFile in imageFiles) {
                val img = Image(ImageDataFactory.create(imgFile.absolutePath)).setAutoScale(true)
                pdfDoc.addNewPage()
                document.add(img)
            }
            document.close()
            Result.success(outputFile)
        } catch (e: Exception) { Result.failure(e) }
    }
}
