package com.propdfeditor.batch.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A batch PDF operation failed for a reason that is safe to show to the user. */
open class PdfOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The PDF needs a password (or the supplied one is wrong). */
class PdfPasswordException(message: String, cause: Throwable? = null) : PdfOperationException(message, cause)

/** The PDF carries digital signatures; modifying it would silently invalidate them. */
class SignedPdfException :
    PdfOperationException("This PDF is digitally signed. Modifying it would invalidate the signature, so it was skipped.")

data class WatermarkParams(
    val text: String,
    val fontSize: Float,
    /** 0..1 */
    val opacity: Float,
    val colorArgb: Int,
    /** Degrees, counter-clockwise positive (PDF convention). Applies to text watermarks only. */
    val rotationDegrees: Float,
    /** TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT; anything else = centre. Text only. */
    val position: String,
    /** When set, an image watermark is drawn instead of text (centred, 200pt wide, unrotated). */
    val imageUri: Uri?
)

data class EncryptParams(
    val password: String,
    val allowPrinting: Boolean,
    val allowCopying: Boolean,
    val allowModifying: Boolean,
    /** AES_256, AES_128, or anything else = RC4 128 (legacy "standard" level). */
    val level: String
)

/** One OCR text line in bitmap pixel coordinates (origin top-left). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** OCR result for one page plus the size of the bitmap the OCR ran on. */
data class OcrPage(val bitmapWidth: Int, val bitmapHeight: Int, val lines: List<OcrLine>)

/**
 * Canonical PDFBox implementation of all batch PDF operations.
 *
 * SAF rule: every URI (content:// or file://) is read/written through ContentResolver. No
 * File(uri.path) anywhere. Inputs are copied to a cache temp file (PDFBox needs random access);
 * outputs are written to a cache temp file first and only then streamed to the destination, so
 * a failed operation never leaves a truncated output.
 */
@Singleton
class PdfProcessor @Inject constructor(
    private val compressionRepository: CompressionRepository
) {

    // ---------------------------------------------------------------- merge / split

    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(inputUris.isNotEmpty()) { "No input files to merge" }
        val sources = mutableListOf<LoadedPdf>()
        val merged = PDDocument(memorySetting(context))
        try {
            val merger = PDFMergerUtility()
            inputUris.forEachIndexed { index, uri ->
                ensureActive()
                try {
                    val source = load(context, uri)
                    // Sources must stay open until the merged document is saved.
                    sources.add(source)
                    if (source.document.isEncrypted) {
                        throw PdfOperationException("An input PDF is encrypted. Decrypt it before merging.")
                    }
                    merger.appendDocument(merged, source.document)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to merge PDF: $uri")
                    throw e
                }
                onProgress(index + 1)
            }
            ensureActive()
            saveTo(context, merged, outputUri)
        } finally {
            closeQuietly(merged)
            sources.forEach { closeQuietly(it) }
        }
    }

    suspend fun splitPdf(
        context: Context,
        inputUri: Uri,
        outputDirUri: Uri,
        pageRanges: List<String>?,
        splitEvery: Int?,
        onProgress: suspend (Int, Int) -> Unit
    ): List<Uri> = withContext(Dispatchers.IO) {
        val resultUris = mutableListOf<Uri>()
        load(context, inputUri).use { source ->
            val document = source.document
            if (document.isEncrypted) {
                throw PdfOperationException("This PDF is encrypted. Decrypt it before splitting.")
            }
            val totalPages = document.numberOfPages
            val ranges = when {
                pageRanges != null -> parsePageRanges(pageRanges, totalPages)
                splitEvery != null -> {
                    require(splitEvery > 0) { "Split size must be at least 1 page" }
                    generateRanges(splitEvery, totalPages)
                }
                else -> listOf(1..totalPages)
            }
            if (ranges.isEmpty()) throw IllegalArgumentException("No valid page ranges to split")

            val parentDir = DocumentFile.fromTreeUri(context, outputDirUri)
                ?: throw IllegalStateException("Cannot access output directory")
            val sourceName = DocumentFile.fromSingleUri(context, inputUri)?.name
                ?.substringBeforeLast(".") ?: "document"

            try {
                ranges.forEachIndexed { index, range ->
                    ensureActive()
                    val startPage = range.first
                    val endPage = range.last

                    val splitter = Splitter().apply {
                        setStartPage(startPage)
                        setEndPage(endPage)
                        setSplitAtPage(endPage - startPage + 1)
                    }
                    val parts = splitter.split(document)
                    try {
                        val part = parts.firstOrNull()
                            ?: throw PdfOperationException("Nothing to write for pages $startPage-$endPage")
                        val outputFile = parentDir.createFile(
                            "application/pdf",
                            "${sourceName}_pages_${startPage}-${endPage}.pdf"
                        ) ?: throw IllegalStateException("Cannot create output file")
                        try {
                            saveTo(context, part, outputFile.uri)
                        } catch (e: Exception) {
                            outputFile.delete()
                            throw e
                        }
                        resultUris.add(outputFile.uri)
                    } finally {
                        parts.forEach { closeQuietly(it) }
                    }
                    onProgress(index + 1, ranges.size)
                }
            } catch (e: Exception) {
                // Failure or cancellation: do not leave a partial set of parts that looks complete.
                resultUris.forEach { created ->
                    try {
                        DocumentFile.fromSingleUri(context, created)?.delete()
                    } catch (cleanup: Exception) {
                        Timber.w(cleanup, "Could not delete partial split output $created")
                    }
                }
                throw e
            }
        }
        resultUris
    }

    // ---------------------------------------------------------------- rotate

    /**
     * Rotates pages by [rotationDegrees] (multiple of 90, clockwise positive) relative to their
     * existing rotation. [pageNumbers] are 1-based; null = all pages.
     */
    suspend fun rotatePdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        rotationDegrees: Int,
        pageNumbers: List<Int>?
    ) = withContext(Dispatchers.IO) {
        require(rotationDegrees % 90 == 0) { "Rotation must be a multiple of 90 degrees" }
        load(context, inputUri).use { source ->
            val document = source.document
            rejectIfSigned(document)
            if (document.isEncrypted && !document.currentAccessPermission.canAssembleDocument()) {
                throw PdfOperationException("This PDF does not permit page changes.")
            }
            val total = document.numberOfPages
            val targets = pageNumbers?.filter { it in 1..total }?.distinct() ?: (1..total).toList()
            if (targets.isEmpty()) throw IllegalArgumentException("No valid pages selected for rotation")
            for (number in targets) {
                val page = document.getPage(number - 1)
                page.rotation = PdfPageGeometry.normalizeRotation(page.rotation + rotationDegrees)
            }
            ensureActive()
            saveTo(context, document, outputUri)
        }
    }

    // ---------------------------------------------------------------- watermark

    suspend fun watermarkPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        params: WatermarkParams
    ) = withContext(Dispatchers.IO) {
        load(context, inputUri).use { source ->
            val document = source.document
            rejectIfSigned(document)
            if (document.isEncrypted && !document.currentAccessPermission.canModify()) {
                throw PdfOperationException("This PDF does not permit modification.")
            }

            val opacity = params.opacity.coerceIn(0f, 1f)
            val graphicsState = PDExtendedGraphicsState()
            graphicsState.setNonStrokingAlphaConstant(opacity)
            graphicsState.setStrokingAlphaConstant(opacity)

            val font = PDType1Font.HELVETICA
            val image = params.imageUri?.let { createWatermarkImage(context, document, it) }
            val text = if (image == null) printableText(font, params.text) else ""
            if (image == null && text.isBlank()) {
                throw IllegalArgumentException("Watermark text has no printable characters")
            }

            for (pageIndex in 0 until document.numberOfPages) {
                ensureActive()
                drawWatermark(document, document.getPage(pageIndex), font, text, image, params, graphicsState)
            }
            saveTo(context, document, outputUri)
        }
    }

    private fun drawWatermark(
        document: PDDocument,
        page: PDPage,
        font: PDFont,
        text: String,
        image: PDImageXObject?,
        params: WatermarkParams,
        graphicsState: PDExtendedGraphicsState
    ) {
        val box = page.cropBox
        val rotation = PdfPageGeometry.normalizeRotation(page.rotation)
        val m = PdfPageGeometry.displayToUser(
            rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height
        )
        val (displayW, displayH) = PdfPageGeometry.displaySize(rotation, box.width, box.height)

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(graphicsState)
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))

            if (image != null) {
                var w = 200f
                var h = w * image.height / image.width.toFloat()
                val fit = minOf(1f, displayW * 0.9f / w, displayH * 0.9f / h)
                w *= fit
                h *= fit
                cs.drawImage(image, displayW / 2f - w / 2f, displayH / 2f - h / 2f, w, h)
            } else {
                val size = params.fontSize.coerceAtLeast(1f)
                val textWidth = font.getStringWidth(text) / 1000f * size
                val capHeight = size * 0.7f
                val (ax, ay) = when (params.position) {
                    "TOP_LEFT" -> 50f to displayH - 50f
                    "TOP_RIGHT" -> displayW - 50f to displayH - 50f
                    "BOTTOM_LEFT" -> 50f to 50f
                    "BOTTOM_RIGHT" -> displayW - 50f to 50f
                    else -> displayW / 2f to displayH / 2f
                }
                cs.setNonStrokingColor(
                    Color.red(params.colorArgb) / 255f,
                    Color.green(params.colorArgb) / 255f,
                    Color.blue(params.colorArgb) / 255f
                )
                cs.transform(Matrix.getTranslateInstance(ax, ay))
                cs.transform(
                    Matrix.getRotateInstance(Math.toRadians(params.rotationDegrees.toDouble()), 0f, 0f)
                )
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(-textWidth / 2f, -capHeight / 2f)
                cs.showText(text)
                cs.endText()
            }
            cs.restoreGraphicsState()
        }
    }

    private fun createWatermarkImage(context: Context, document: PDDocument, uri: Uri): PDImageXObject {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw IOException("Cannot open watermark image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot decode watermark image")

        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open watermark image")
        val bitmap: Bitmap = stream.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode watermark image")
        try {
            // Lossless keeps PNG transparency (soft mask).
            return LosslessFactory.createFromImage(document, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------- encrypt / decrypt

    suspend fun encryptPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        params: EncryptParams
    ) = withContext(Dispatchers.IO) {
        require(params.password.isNotEmpty()) { "A password is required" }
        // PDFBox encodes RC4/AES-128 passwords as ISO-8859-1. Anything outside Latin-1 would be
        // silently replaced by '?', so the file would also open with a lossy substitute. Refuse
        // instead of weakening the protection; AES-256 uses UTF-8 and has no such limit.
        if (params.level != "AES_256" &&
            !Charsets.ISO_8859_1.newEncoder().canEncode(params.password)
        ) {
            throw PdfOperationException(
                "This password contains characters that the selected encryption level cannot " +
                    "store. Use AES-256 or a password with Latin characters only."
            )
        }
        load(context, inputUri).use { source ->
            val document = source.document
            rejectIfSigned(document)
            if (document.isEncrypted && !document.currentAccessPermission.isOwnerPermission) {
                throw PdfOperationException("This PDF is already protected. Decrypt it with its owner password first.")
            }
            // Same mapping as the previous implementation: only the three exposed permissions are
            // granted, everything else is denied.
            val permissions = AccessPermission().apply {
                setCanPrint(params.allowPrinting)
                setCanPrintDegraded(params.allowPrinting)
                setCanExtractContent(params.allowCopying)
                setCanModify(params.allowModifying)
                setCanModifyAnnotations(false)
                setCanFillInForm(false)
                setCanExtractForAccessibility(false)
                setCanAssembleDocument(false)
            }
            // Previous behaviour: user password == owner password.
            val policy = StandardProtectionPolicy(params.password, params.password, permissions)
            when (params.level) {
                "AES_256" -> { policy.setEncryptionKeyLength(256); policy.setPreferAES(true) }
                "AES_128" -> { policy.setEncryptionKeyLength(128); policy.setPreferAES(true) }
                else -> { policy.setEncryptionKeyLength(128); policy.setPreferAES(false) }
            }
            document.protect(policy)
            ensureActive()
            saveTo(context, document, outputUri)
        }
    }

    suspend fun decryptPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        password: String
    ) = withContext(Dispatchers.IO) {
        load(context, inputUri, password).use { source ->
            val document = source.document
            rejectIfSigned(document)
            if (document.isEncrypted) {
                if (!document.currentAccessPermission.isOwnerPermission) {
                    throw PdfOperationException(
                        "The owner password is required to remove this PDF's protection."
                    )
                }
                document.setAllSecurityToBeRemoved(true)
            }
            ensureActive()
            saveTo(context, document, outputUri)
        }
    }

    // ---------------------------------------------------------------- compress

    /** Delegates to the single PDFBox compression implementation in :core. */
    suspend fun compressPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        config: CompressionConfig
    ): CompressionResult {
        assertNotSigned(context, inputUri)
        var result: CompressionResult? = null
        compressionRepository.compress(inputUri.toString(), outputUri.toString(), config).collect { event ->
            when (event) {
                is AppResult.Success -> result = event.data
                is AppResult.Error -> throw event.exception
                is AppResult.Loading -> Unit
            }
        }
        return result ?: throw PdfOperationException("Compression finished without a result")
    }

    /**
     * The compression repository rewrites the whole file, so signed PDFs are refused up front.
     * A password-protected input cannot be inspected here; it is left to the repository, which
     * has no password and fails on its own.
     */
    private suspend fun assertNotSigned(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val source = try {
            load(context, uri)
        } catch (e: PdfPasswordException) {
            return@withContext
        }
        source.use { rejectIfSigned(it.document) }
    }

    // ---------------------------------------------------------------- OCR text layer

    /**
     * Adds an invisible (render mode 3) text layer to the pages of [sourceFile] and writes the
     * result to [outputUri]. Original page content is untouched.
     */
    suspend fun writeSearchablePdf(
        context: Context,
        sourceFile: File,
        outputUri: Uri,
        pages: List<OcrPage>
    ) = withContext(Dispatchers.IO) {
        PDDocument.load(sourceFile, memorySetting(context)).use { document ->
            if (document.isEncrypted) throw PdfOperationException("This PDF is encrypted.")
            rejectIfSigned(document)
            if (document.numberOfPages != pages.size) {
                throw PdfOperationException(
                    "Page count mismatch (${document.numberOfPages} vs ${pages.size} OCR pages)"
                )
            }
            val font = PDType1Font.HELVETICA
            pages.forEachIndexed { index, ocrPage ->
                ensureActive()
                if (ocrPage.lines.isNotEmpty()) {
                    drawInvisibleText(document, document.getPage(index), font, ocrPage)
                }
            }
            saveTo(context, document, outputUri)
        }
    }

    private fun drawInvisibleText(document: PDDocument, page: PDPage, font: PDFont, ocrPage: OcrPage) {
        if (ocrPage.bitmapWidth <= 0 || ocrPage.bitmapHeight <= 0) return
        val box = page.cropBox
        val rotation = PdfPageGeometry.normalizeRotation(page.rotation)
        val m = PdfPageGeometry.displayToUser(rotation, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
        val (displayW, displayH) = PdfPageGeometry.displaySize(rotation, box.width, box.height)
        val sx = displayW / ocrPage.bitmapWidth
        val sy = displayH / ocrPage.bitmapHeight

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            for (line in ocrPage.lines) {
                val text = printableText(font, line.text)
                if (text.isBlank()) continue
                val boxH = (line.bottom - line.top) * sy
                val boxW = (line.right - line.left) * sx
                if (boxH <= 0f || boxW <= 0f) continue
                val size = (boxH * 0.85f).coerceIn(1f, 200f)
                val natural = font.getStringWidth(text) / 1000f * size
                if (natural <= 0f) continue
                val hScale = (boxW / natural).coerceIn(0.05f, 20f)
                val x = line.left * sx
                val baseline = displayH - line.bottom * sy + boxH * 0.2f
                cs.setFont(font, size)
                cs.setTextMatrix(Matrix(hScale, 0f, 0f, 1f, x, baseline))
                cs.showText(text)
            }
            cs.endText()
            cs.restoreGraphicsState()
        }
    }

    // ---------------------------------------------------------------- shared helpers

    private class LoadedPdf(val document: PDDocument, private val tempFile: File?) : Closeable {
        override fun close() {
            try {
                document.close()
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun memorySetting(context: Context): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

    private fun load(context: Context, uri: Uri, password: String? = null): LoadedPdf {
        val temp = File.createTempFile("batch_src_", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open input file")
            input.use { source -> temp.outputStream().use { sink -> source.copyTo(sink) } }
            val document = PDDocument.load(temp, password ?: "", memorySetting(context))
            return LoadedPdf(document, temp)
        } catch (e: InvalidPasswordException) {
            temp.delete()
            throw PdfPasswordException(
                if (password.isNullOrEmpty()) "This PDF is password protected." else "Incorrect password.",
                e
            )
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Saves to a cache temp file, then streams to [outputUri] (truncating). */
    private fun saveTo(context: Context, document: PDDocument, outputUri: Uri) {
        val temp = File.createTempFile("batch_out_", ".pdf", context.cacheDir)
        try {
            document.save(temp)
            val output = context.contentResolver.openOutputStream(outputUri, "wt")
                ?: throw IOException("Cannot write to output file")
            output.use { sink -> temp.inputStream().use { it.copyTo(sink) } }
        } finally {
            temp.delete()
        }
    }

    /**
     * Standard-14 Helvetica only encodes WinAnsi. Newlines become spaces and characters the font
     * cannot encode are dropped (logged) instead of aborting the whole document.
     */
    private fun printableText(font: PDFont, text: String): String {
        var dropped = 0
        val result = buildString {
            for (ch in text) {
                if (ch == '\n' || ch == '\r' || ch == '\t') {
                    append(' ')
                    continue
                }
                try {
                    font.encode(ch.toString())
                    append(ch)
                } catch (e: IllegalArgumentException) {
                    dropped++
                }
            }
        }
        if (dropped > 0) Timber.w("Dropped $dropped character(s) not encodable in Helvetica")
        return result.trim()
    }

    /**
     * Signed (or certified) PDFs are never rewritten: any full save invalidates the signature,
     * and a result that merely looks like the original would be misleading. Detection is
     * content-based: signature dictionaries reachable from the AcroForm, or a /Perms /DocMDP
     * certification entry in the catalog.
     */
    private fun isSigned(document: PDDocument): Boolean {
        if (document.signatureDictionaries.isNotEmpty()) return true
        val perms = document.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
        return perms != null && perms.containsKey(COSName.getPDFName("DocMDP"))
    }

    private fun rejectIfSigned(document: PDDocument) {
        if (isSigned(document)) throw SignedPdfException()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: Exception) {
            Timber.w(e, "Failed to close PDF resource")
        }
    }

    private fun parsePageRanges(ranges: List<String>, totalPages: Int): List<IntRange> {
        return ranges.mapNotNull { rangeStr ->
            when {
                rangeStr.contains("-") -> {
                    val parts = rangeStr.split("-")
                    val start = parts[0].trim().toIntOrNull()?.coerceIn(1, totalPages) ?: return@mapNotNull null
                    val end = parts[1].trim().toIntOrNull()?.coerceIn(start, totalPages) ?: return@mapNotNull null
                    start..end
                }
                else -> {
                    val page = rangeStr.trim().toIntOrNull()?.coerceIn(1, totalPages) ?: return@mapNotNull null
                    page..page
                }
            }
        }
    }

    private fun generateRanges(splitEvery: Int, totalPages: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var current = 1
        while (current <= totalPages) {
            val end = (current + splitEvery - 1).coerceAtMost(totalPages)
            ranges.add(current..end)
            current = end + 1
        }
        return ranges
    }
}
