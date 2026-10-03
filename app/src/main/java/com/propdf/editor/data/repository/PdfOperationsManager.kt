package com.propdf.editor.data.repository

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.model.HeaderFooterConfig
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.editor.core.dispatch.ThreadPoolManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-based PDF operations used by [com.propdf.editor.worker.PdfOperationWorker]'s batch path.
 *
 * Engine: PDFBox Android 2.0.27.0 via [PdfBoxPageEngine]. This class contains no iText.
 * Compress / encrypt / remove-password are NOT Page Editor operations; they delegate unchanged to
 * [LegacyITextPdfOperations] until their own migration phases.
 *
 * Every operation runs on the background dispatcher, returns Result, never converts coroutine
 * cancellation into a failure, and never leaves a partial output file (see [PdfBoxPageEngine]).
 */
class PdfOperationsManager(private val context: Context) {

    private val engine by lazy { PdfBoxPageEngine(context) }
    private val legacy by lazy { LegacyITextPdfOperations(context) }

    private suspend fun <T> op(block: suspend () -> T): Result<T> =
        withContext(ThreadPoolManager.BackgroundDispatcher) {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun mergePdfs(files: List<File>, output: File): Result<File> = op {
        require(files.size >= 2) { "Need at least 2 files" }
        engine.merge(files, output)
    }

    suspend fun splitPdf(file: File, outDir: File, ranges: List<IntRange>): Result<List<File>> = op {
        outDir.mkdirs()
        engine.splitRanges(file, ranges) { idx -> File(outDir, "${file.nameWithoutExtension}_part${idx + 1}.pdf") }
    }

    suspend fun compressPdf(file: File, output: File, level: Int = 6): Result<File> =
        legacy.compressPdf(file, output, level)

    suspend fun encryptPdf(file: File, output: File, userPassword: String, ownerPassword: String): Result<File> =
        legacy.encryptPdf(file, output, userPassword, ownerPassword)

    suspend fun removePdfPassword(file: File, output: File, password: String): Result<File> =
        legacy.removePdfPassword(file, output, password)

    suspend fun addTextWatermark(file: File, output: File, text: String, opacity: Float = 0.3f): Result<File> = op {
        engine.watermark(
            file, output,
            WatermarkConfig(text = text, opacity = opacity, rotation = 30f, fontSize = 72f, color = 0xFFFF0000.toInt())
        )
    }

    suspend fun rotatePages(file: File, output: File, rotations: Map<Int, Int>): Result<File> = op {
        engine.rotate(file, output, rotations)
    }

    suspend fun deletePages(file: File, output: File, pagesToDelete: List<Int>): Result<File> = op {
        val order = PageOrderPlanner.deleteOrder(engine.pageCount(file), pagesToDelete)
        require(order.isNotEmpty()) { "A document must keep at least one page." }
        engine.assemble(file, output, order)
    }

    suspend fun addPageNumbers(file: File, output: File, format: String = "Page %d of %d"): Result<File> = op {
        engine.pageNumbers(file, output, PageNumberConfig(format = format))
    }

    suspend fun addHeaderFooter(file: File, output: File, header: String?, footer: String?): Result<File> = op {
        engine.headerFooter(file, output, HeaderFooterConfig(headerText = header, footerText = footer))
    }

    suspend fun imagesToPdf(images: List<File>, output: File): Result<File> = op {
        val usable = images.filter { it.exists() && it.length() > 0 }.map { Uri.fromFile(it) }
        engine.imagesToPdf(usable, output, null)
    }
}
