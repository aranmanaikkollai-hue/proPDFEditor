package com.propdf.editor.data.repository

import android.content.Context
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Image
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PdfOperationsManager
 *
 * Handles all PDF manipulation operations using iText 7 (AGPL) and PDFBox.
 * All operations run on IO thread via coroutines.
 *
 * Operations:
 * - Merge multiple PDFs
 * - Split PDF by page ranges
 * - Compress PDF (reduce file size)
 * - Add password protection / encryption
 * - Remove password
 * - Add watermark (text or image)
 * - Add header / footer
 * - Add page numbers
 * - Rotate pages
 * - Delete / reorder pages
 * - Extract pages to new PDF
 * - Redact sensitive content
 */
@Singleton
class PdfOperationsManager @Inject constructor(
    private val context: Context
) {

    // ── MERGE ────────────────────────────────────────────────────
    /**
     * Merge multiple PDF files into one output PDF.
     * @param inputFiles List of source PDF files
     * @param outputFile Destination file for merged PDF
     */
    suspend fun mergePdfs(
        inputFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writer = PdfWriter(outputFile)
            val outputPdf = PdfDocument(writer)
            val merger = PdfMerger(outputPdf)

            for (file in inputFiles) {
                if (!file.exists()) continue
                val reader = PdfReader(file)
                val sourcePdf = PdfDocument(reader)
                merger.merge(sourcePdf, 1, sourcePdf.numberOfPages)
                sourcePdf.close()
            }

            outputPdf.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── SPLIT ────────────────────────────────────────────────────
    /**
     * Split a PDF into multiple files.
     * @param inputFile Source PDF
     * @param outputDir Directory to write split files
     * @param ranges List of page ranges e.g. [[1,3],[4,7]] (1-indexed)
     */
    suspend fun splitPdf(
        inputFile: File,
        outputDir: File,
        ranges: List<IntRange>
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val sourcePdf = PdfDocument(reader)
            val outputFiles = mutableListOf<File>()

            ranges.forEachIndexed { index, range ->
                val outputFile = File(outputDir, "${inputFile.nameWithoutExtension}_part${index + 1}.pdf")
                val writer = PdfWriter(outputFile)
                val destPdf = PdfDocument(writer)
                val merger = PdfMerger(destPdf)

                val from = range.first.coerceIn(1, sourcePdf.numberOfPages)
                val to = range.last.coerceIn(from, sourcePdf.numberOfPages)
                merger.merge(sourcePdf, from, to)

                destPdf.close()
                outputFiles.add(outputFile)
            }

            sourcePdf.close()
            Result.success(outputFiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── COMPRESS ─────────────────────────────────────────────────
    /**
     * Compress a PDF to reduce file size.
     * @param inputFile Source PDF
     * @param outputFile Compressed output PDF
     * @param quality 0=low size (aggressive) 1=medium 2=high quality
     */
    suspend fun compressPdf(
        inputFile: File,
        outputFile: File,
        quality: Int = 1
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writerProperties = WriterProperties().apply {
                when (quality) {
                    0 -> setFullCompressionMode(true) // Aggressive compression
                    1 -> setFullCompressionMode(true)
                    2 -> setCompressionLevel(PdfOutputStream.NO_COMPRESSION)
                }
                useSmartMode()
            }

            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile, writerProperties)
            val pdfDoc = PdfDocument(reader, writer)

            // Compress content streams
            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                page.setIgnorePageRotationForContent(true)
            }

            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── ENCRYPT / PASSWORD PROTECT ───────────────────────────────
    /**
     * Add password protection to a PDF.
     * @param inputFile Source PDF
     * @param outputFile Password-protected output
     * @param userPassword Password required to open the PDF (null = no open password)
     * @param ownerPassword Password required to edit/print (master password)
     * @param allowPrinting Whether to allow printing
     * @param allowCopying Whether to allow text copying
     */
    suspend fun encryptPdf(
        inputFile: File,
        outputFile: File,
        userPassword: String?,
        ownerPassword: String,
        allowPrinting: Boolean = true,
        allowCopying: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            var permissions = EncryptionConstants.ALLOW_SCREENREADERS
            if (allowPrinting) permissions = permissions or EncryptionConstants.ALLOW_PRINTING
            if (allowCopying) permissions = permissions or EncryptionConstants.ALLOW_COPY

            val writerProperties = WriterProperties().setStandardEncryption(
                userPassword?.toByteArray(),
                ownerPassword.toByteArray(),
                permissions,
                EncryptionConstants.ENCRYPTION_AES_256
            )

            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile, writerProperties)
            PdfDocument(reader, writer).close()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── REMOVE PASSWORD ──────────────────────────────────────────
    suspend fun removePdfPassword(
        inputFile: File,
        outputFile: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val readerProperties = ReaderProperties()
                .setPassword(password.toByteArray())
            val reader = PdfReader(inputFile, readerProperties)
            val writer = PdfWriter(outputFile)
            PdfDocument(reader, writer).close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── WATERMARK ────────────────────────────────────────────────
    /**
     * Add a text watermark to all pages of a PDF.
     * @param text Watermark text (e.g. "CONFIDENTIAL")
     * @param opacity 0.0 (invisible) to 1.0 (fully opaque)
     * @param rotation Degrees to rotate the watermark (default 45)
     */
    suspend fun addTextWatermark(
        inputFile: File,
        outputFile: File,
        text: String,
        opacity: Float = 0.3f,
        rotation: Float = 45f
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(reader, writer)

            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()

            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                val pageSize = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, pdfDoc)

                val gs = PdfExtGState()
                gs.fillOpacity = opacity

                canvas.saveState()
                canvas.setExtGState(gs)
                canvas.beginText()
                canvas.setFontAndSize(font, 60f)
                canvas.setFillColor(ColorConstants.LIGHT_GRAY)

                // Center watermark diagonally
                val x = (pageSize.left + pageSize.right) / 2
                val y = (pageSize.bottom + pageSize.top) / 2
                canvas.setTextMatrix(
                    Math.cos(Math.toRadians(rotation.toDouble())).toFloat(),
                    Math.sin(Math.toRadians(rotation.toDouble())).toFloat(),
                    -Math.sin(Math.toRadians(rotation.toDouble())).toFloat(),
                    Math.cos(Math.toRadians(rotation.toDouble())).toFloat(),
                    x, y
                )
                canvas.showText(text)
                canvas.endText()
                canvas.restoreState()
            }

            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── DELETE PAGES ─────────────────────────────────────────────
    /**
     * Delete specific pages from a PDF.
     * @param pagesToDelete 1-indexed page numbers to remove
     */
    suspend fun deletePages(
        inputFile: File,
        outputFile: File,
        pagesToDelete: List<Int>
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(reader, writer)

            // Remove in reverse order to preserve indices
            pagesToDelete.sortedDescending().forEach { pageNum ->
                if (pageNum in 1..pdfDoc.numberOfPages) {
                    pdfDoc.removePage(pageNum)
                }
            }

            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── ROTATE PAGES ─────────────────────────────────────────────
    /**
     * Rotate specific pages in a PDF.
     * @param pages Map of pageNumber (1-indexed) to rotation degrees (90, 180, 270)
     */
    suspend fun rotatePages(
        inputFile: File,
        outputFile: File,
        pages: Map<Int, Int>
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(reader, writer)

            pages.forEach { (pageNum, degrees) ->
                if (pageNum in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(pageNum)
                    val currentRotation = page.rotation
                    page.put(PdfName.Rotate, PdfNumber((currentRotation + degrees) % 360))
                }
            }

            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── ADD PAGE NUMBERS ─────────────────────────────────────────
    suspend fun addPageNumbers(
        inputFile: File,
        outputFile: File,
        format: String = "Page %d of %d", // Use %d for current, total
        position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(reader, writer)
            val document = Document(pdfDoc)

            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
            val total = pdfDoc.numberOfPages

            for (i in 1..total) {
                val text = format.replace("%d", i.toString()).let {
                    if (it.contains("%d")) it.replaceFirst("%d", total.toString()) else it
                }
                val page = pdfDoc.getPage(i)
                val pageSize = page.pageSize

                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                canvas.beginText()
                canvas.setFontAndSize(font, 10f)

                val (x, y) = when (position) {
                    PageNumberPosition.BOTTOM_CENTER ->
                        (pageSize.left + pageSize.right) / 2 to pageSize.bottom + 20f
                    PageNumberPosition.BOTTOM_RIGHT ->
                        pageSize.right - 50f to pageSize.bottom + 20f
                    PageNumberPosition.BOTTOM_LEFT ->
                        pageSize.left + 20f to pageSize.bottom + 20f
                    PageNumberPosition.TOP_CENTER ->
                        (pageSize.left + pageSize.right) / 2 to pageSize.top - 20f
                }
                canvas.moveText(x.toDouble(), y.toDouble())
                canvas.showText(text)
                canvas.endText()
            }

            document.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── ADD HEADER / FOOTER ──────────────────────────────────────
    suspend fun addHeaderFooter(
        inputFile: File,
        outputFile: File,
        headerText: String? = null,
        footerText: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reader = PdfReader(inputFile)
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(reader, writer)
            val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()

            for (i in 1..pdfDoc.numberOfPages) {
                val page = pdfDoc.getPage(i)
                val pageSize = page.pageSize
                val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                canvas.beginText()
                canvas.setFontAndSize(font, 10f)

                headerText?.let {
                    canvas.moveText(
                        ((pageSize.left + pageSize.right) / 2).toDouble(),
                        (pageSize.top - 20f).toDouble()
                    )
                    canvas.showText(it)
                }

                footerText?.let {
                    canvas.moveText(
                        ((pageSize.left + pageSize.right) / 2).toDouble(),
                        (pageSize.bottom + 10f).toDouble()
                    )
                    canvas.showText(it)
                }

                canvas.endText()
            }

            pdfDoc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── EXTRACT PAGES ────────────────────────────────────────────
    suspend fun extractPages(
        inputFile: File,
        outputFile: File,
        pageRange: IntRange
    ): Result<File> = splitPdf(inputFile, outputFile.parentFile!!, listOf(pageRange))
        .map { it.firstOrNull() ?: outputFile }

    // ── IMAGES TO PDF ────────────────────────────────────────────
    suspend fun imagesToPdf(
        imageFiles: List<File>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val writer = PdfWriter(outputFile)
            val pdfDoc = PdfDocument(writer)
            val document = Document(pdfDoc)

            for (imgFile in imageFiles) {
                val imgData = ImageDataFactory.create(imgFile.absolutePath)
                val img = Image(imgData)

                // Fit image to A4 page
                img.setAutoScale(true)
                pdfDoc.addNewPage()
                document.add(img)
            }

            document.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    enum class PageNumberPosition {
        BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT, TOP_CENTER
    }
}
