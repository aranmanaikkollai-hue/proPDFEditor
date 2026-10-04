package com.propdf.security.redaction

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.propdf.core.domain.result.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * File-output convenience wrapper over [PdfBoxRedactionEngine] (secure "burn in and rebuild"
 * redaction; see that class for exactly what is and is not guaranteed).
 *
 * Replaces the former iText version, which only painted black rectangles (or a whole black page)
 * over content that stayed in the file, and which read the source with `File(sourceUri.path)`.
 * Sources may now be file:// or content:// and are staged through the ContentResolver.
 *
 * Region rectangles use the app's RedactionEntity convention: `left`/`bottom` is the lower-left corner,
 * `width()`/`height()` the size, in the DISPLAY space of the page (see [RedactionArea]).
 */
class RedactionEngine(private val context: Context) {

    private val engine by lazy { PdfBoxRedactionEngine(context) }

    private fun stage(sourceUri: Uri): File {
        val staged = File.createTempFile("pdf_redact_src", ".pdf", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw FileNotFoundException("Source is no longer available")
            input.use { src -> staged.outputStream().use { dst -> src.copyTo(dst) } }
            if (staged.length() == 0L) throw AppException.FileNotFound("Source is empty")
            return staged
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
    }

    /** Redacts the given regions. The whole page of every region's page is rebuilt from pixels. */
    suspend fun redactRegions(
        sourceUri: Uri,
        outputFile: File,
        redactions: List<RedactionRegion>
    ): Result<File> = withContext(Dispatchers.IO) {
        var staged: File? = null
        try {
            staged = stage(sourceUri)
            val areas = redactions.map {
                RedactionArea(it.pageNumber, it.rect.left, it.rect.bottom, it.rect.width(), it.rect.height(), it.label)
            }
            engine.redact(staged, outputFile, areas)
            Result.success(outputFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged?.delete()
        }
    }

    /**
     * Redacts every page whose text contains [searchText]. The match is page-level: each matching page is
     * redacted completely (rebuilt as a solid black image), the same page-level behaviour the old method had,
     * except that the text is now really removed.
     */
    suspend fun redactText(
        sourceUri: Uri,
        outputFile: File,
        searchText: String,
        caseSensitive: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        var staged: File? = null
        try {
            staged = stage(sourceUri)
            val pages = engine.pagesContainingText(staged, searchText, caseSensitive)
            if (pages.isEmpty()) {
                // Nothing matched: an unchanged copy, as before.
                staged.copyTo(outputFile, overwrite = true)
            } else {
                val areas = pages.map { page ->
                    val size = pageSize(staged, page)
                    RedactionArea(page, 0f, 0f, size.first, size.second)
                }
                engine.redact(staged, outputFile, areas)
            }
            Result.success(outputFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged?.delete()
        }
    }

    private fun pageSize(file: File, pageNumber: Int): Pair<Float, Float> {
        val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                r.openPage(pageNumber - 1).use { p -> return p.width.toFloat() to p.height.toFloat() }
            }
        } finally {
            pfd.close()
        }
    }
}

data class RedactionRegion(
    val pageNumber: Int,
    val rect: RectF,
    val label: String? = null
)
