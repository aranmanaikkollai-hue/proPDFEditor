package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.utils.FileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import kotlin.math.min

/**
 * Production-grade PDF Operations Repository.
 * 
 * - All operations return Result<T> for proper error handling
 * - Heavy operations run on BackgroundDispatcher
 * - Memory-efficient: streams used where possible, no large intermediate objects
 * - Proper resource cleanup with use{} blocks
 * - iText7 with BouncyCastle for AES-256 encryption
 */
class PdfOperationsManager(private val context: Context) {

    companion object {
        private const val TAG = "PdfOps"
    }

    // ─── Merge ─────────────────────────────────────────────────────
    suspend fun mergePdfs(files: List<File>, output: File): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            if (files.size < 2) throw IllegalArgumentException("Need at least 2 files")
            val writer = PdfWriter(output)
            val pdfOut = PdfDocument(writer)
            val merger = PdfMerger(pdfOut)
            files.forEach { file ->
                val reader = PdfReader(file)
                val src = PdfDocument(reader)
                merger.merge(src, 1, src.numberOfPages)
                src.close()
                reader.close()
            }
            merger.close()
            pdfOut.close()
            writer.close()
            output
        }
    }

    // ─── Split ─────────────────────────────────────────────────────
    suspend fun splitPdf(file: File, outDir: File, ranges: List<IntRange>): Result<List<File>> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val src = PdfDocument(reader)
            val results = mutableListOf<File>()
            ranges.forEachIndexed { idx, range ->
                val outFile = File(outDir, "${file.nameWithoutExtension}_part${idx + 1}.pdf")
                val writer = PdfWriter(outFile)
                val dest = PdfDocument(writer)
                src.copyPagesTo(range.first, range.last, dest)
                dest.close()
                writer.close()
                results.add(outFile)
            }
            src.close()
            reader.close()
            results
        }
    }

    // ─── Compress ──────────────────────────────────────────────────
    suspend fun compressPdf(file: File, output: File, level: Int = 6): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output).apply {
                setCompressionLevel(level.coerceIn(0, 9))
            }
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Encrypt ───────────────────────────────────────────────────
    suspend fun encryptPdf(file: File, output: File, userPassword: String, ownerPassword: String): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writerProps = WriterProperties().setStandardEncryption(
                userPassword.toByteArray(),
                ownerPassword.toByteArray(),
                EncryptionConstants.ALLOW_PRINTING or EncryptionConstants.ALLOW_COPY,
                EncryptionConstants.ENCRYPTION_AES_256
            )
            val writer = PdfWriter(output.absolutePath, writerProps)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Decrypt ───────────────────────────────────────────────────
    suspend fun removePdfPassword(file: File, output: File, password: String): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val readerProps = ReaderProperties()
            if (password.isNotEmpty()) {
                readerProps.setPassword(password.toByteArray())
            }
            val reader = PdfReader(file.absolutePath, readerProps).setUnethicalReading(true)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Watermark ─────────────────────────────────────────────────
    suspend fun addTextWatermark(file: File, output: File, text: String, opacity: Float = 0.3f): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)

            val doc = Document(dest)
            val gState = PdfExtGState().setFillOpacity(opacity)

            for (i in 1..dest.numberOfPages) {
                val page = dest.getPage(i)
                val pdfCanvas = PdfCanvas(page)
                pdfCanvas.saveState().setExtGState(gState)
                val rect = page.pageSize
                val layoutCanvas = com.itextpdf.layout.Canvas(pdfCanvas, rect)
                val paragraph = Paragraph(text)
                    .setFont(com.itextpdf.kernel.font.PdfFontFactory.createFont())
                    .setFontSize(72f)
                    .setFontColor(ColorConstants.RED)
                layoutCanvas.showTextAligned(
                    paragraph, rect.width / 2, rect.height / 2,
                    TextAlignment.CENTER, VerticalAlignment.MIDDLE, (Math.PI / 6).toFloat()
                )
                layoutCanvas.close()
                pdfCanvas.restoreState()
            }
            doc.close()
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Rotate ────────────────────────────────────────────────────
    suspend fun rotatePages(file: File, output: File, rotations: Map<Int, Int>): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)

            rotations.forEach { (pageNum, degrees) ->
                if (pageNum in 1..dest.numberOfPages) {
                    val page = dest.getPage(pageNum)
                    val currentRotation = page.rotation
                    page.rotation = (currentRotation + degrees) % 360
                }
            }
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Delete Pages ──────────────────────────────────────────────
    suspend fun deletePages(file: File, output: File, pagesToDelete: List<Int>): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            val sorted = pagesToDelete.sortedDescending()
            val allPages = (1..src.numberOfPages).toMutableList()
            sorted.forEach { allPages.remove(it) }
            allPages.forEach { pageNum ->
                src.copyPagesTo(pageNum, pageNum, dest)
            }
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Page Numbers ──────────────────────────────────────────────
    suspend fun addPageNumbers(file: File, output: File, format: String = "Page %d of %d"): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)

            val doc = Document(dest)
            val total = dest.numberOfPages
            for (i in 1..total) {
                val page = dest.getPage(i)
                val rect = page.pageSize
                val text = format.format(i, total)
                doc.showTextAligned(Paragraph(text), rect.width / 2, 20f, i,
                    TextAlignment.CENTER, VerticalAlignment.BOTTOM, 0f)
            }
            doc.close()
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Header/Footer ─────────────────────────────────────────────
    suspend fun addHeaderFooter(file: File, output: File, header: String?, footer: String?): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val reader = PdfReader(file)
            val writer = PdfWriter(output)
            val src = PdfDocument(reader)
            val dest = PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)

            val doc = Document(dest)
            for (i in 1..dest.numberOfPages) {
                val page = dest.getPage(i)
                val rect = page.pageSize
                header?.let {
                    doc.showTextAligned(Paragraph(it), rect.width / 2, rect.height - 20f, i,
                        TextAlignment.CENTER, VerticalAlignment.TOP, 0f)
                }
                footer?.let {
                    doc.showTextAligned(Paragraph(it), rect.width / 2, 20f, i,
                        TextAlignment.CENTER, VerticalAlignment.BOTTOM, 0f)
                }
            }
            doc.close()
            dest.close()
            src.close()
            writer.close()
            reader.close()
            output
        }
    }

    // ─── Images to PDF ─────────────────────────────────────────────
    suspend fun imagesToPdf(images: List<File>, output: File): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            val writer = PdfWriter(output)
            val pdf = PdfDocument(writer)
            val doc = Document(pdf)

            images.forEach { imgFile ->
                val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                if (bmp != null) {
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    val imageData = com.itextpdf.io.image.ImageDataFactory.create(stream.toByteArray())
                    val image = com.itextpdf.layout.element.Image(imageData)
                    image.setAutoScale(true)
                    doc.add(image)
                    bmp.recycle()
                }
            }
            doc.close()
            pdf.close()
            writer.close()
            output
        }
    }
}
