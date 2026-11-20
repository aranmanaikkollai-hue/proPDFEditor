package com.propdf.pdfedit.convert

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PdfToImagesOptions(
    val format: ImageFormat = ImageFormat.PNG,
    /** Requested resolution; clamped to 36..600 and lowered when a page would exceed [maxPixels]. */
    val dpi: Int = 150,
    val jpegQuality: Int = 90,
    val pages: PageSelection = PageSelection.All,
    /** Upper limit of pixels per page image (about 100 MB of bitmap memory at the default). */
    val maxPixels: Long = 25_000_000L
)

/**
 * Renders PDF pages to PNG or JPEG files with the platform PdfRenderer.
 *
 * Every renderer call is serialized by [rendererMutex]. Pages are drawn on a white background (PdfRenderer leaves
 * page areas without a background transparent). If anything fails or the coroutine is cancelled, every file written
 * so far is deleted through the sink. The input file is only read.
 */
class PdfToImagesConverter {
    private val rendererMutex = Mutex()

    suspend fun convert(
        pdf: File,
        baseName: String,
        options: PdfToImagesOptions,
        sink: OutputSink,
        tempDir: File,
        password: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ConvertReport = withContext(Dispatchers.IO) {
        val created = ArrayList<String>()
        var unlocked: File? = null
        try {
            var opened = openRenderer(pdf)
            if (opened == null) {
                if (password.isNullOrEmpty()) {
                    throw ConvertException(ConvertException.Reason.PASSWORD_REQUIRED, "This PDF needs a password")
                }
                unlocked = PdfUnlock.decryptToTemp(pdf, password, tempDir)
                opened = openRenderer(unlocked)
                    ?: throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF could not be read")
            }
            val (renderer, descriptor) = opened
            try {
                val total = renderer.pageCount
                if (total <= 0) throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF has no pages")
                val indices = when (val sel = options.pages) {
                    PageSelection.All -> (0 until total).toList()
                    is PageSelection.Ranges -> when (val parsed = PageRanges.parse(sel.spec, total)) {
                        is PageRanges.Result.Ok -> parsed.indices
                        is PageRanges.Result.Error -> throw ConvertException(ConvertException.Reason.INVALID_RANGE, parsed.message)
                    }
                }
                if (indices.isEmpty()) throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "No pages selected")

                val base = OutputNaming.baseName(baseName)
                val warnings = LinkedHashSet<ConvertWarning>()
                var minDpi = Double.MAX_VALUE
                for ((n, pageIndex) in indices.withIndex()) {
                    ensureActive()
                    val dpiUsed = rendererMutex.withLock {
                        renderer.openPage(pageIndex).use { page ->
                            val size = RenderSizing.compute(page.width.toDouble(), page.height.toDouble(), options.dpi, options.maxPixels)
                            if (size.reduced) warnings.add(ConvertWarning.RESOLUTION_REDUCED)
                            val bitmap = try {
                                Bitmap.createBitmap(size.widthPx, size.heightPx, Bitmap.Config.ARGB_8888)
                            } catch (e: OutOfMemoryError) {
                                throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory for this page", e)
                            }
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val name = OutputNaming.pageImageName(base, pageIndex + 1, total, options.format.extension)
                                val compressFormat = if (options.format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                sink.open(name, options.format.mimeType).use { out ->
                                    created.add(name)
                                    if (!bitmap.compress(compressFormat, options.jpegQuality.coerceIn(1, 100), out)) {
                                        throw IOException("The image could not be encoded")
                                    }
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            size.effectiveDpi
                        }
                    }
                    if (dpiUsed < minDpi) minDpi = dpiUsed
                    onProgress(n + 1, indices.size)
                }
                ConvertReport(created.toList(), indices.size, total, warnings, minDpi)
            } finally {
                try { renderer.close() } catch (e: Exception) { /* closing is best effort */ }
                try { descriptor.close() } catch (e: Exception) { /* closing is best effort */ }
            }
        } catch (e: CancellationException) {
            discard(sink, created)
            throw e
        } catch (e: ConvertException) {
            discard(sink, created)
            throw e
        } catch (e: OutOfMemoryError) {
            discard(sink, created)
            throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory", e)
        } catch (e: Exception) {
            discard(sink, created)
            throw ConvertException(ConvertException.Reason.WRITE_FAILED, "The images could not be written", e)
        } finally {
            unlocked?.delete()
        }
    }

    /** Opens a renderer, or returns null when the document needs a password. */
    private fun openRenderer(file: File): Pair<PdfRenderer, ParcelFileDescriptor>? {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF could not be opened", e)
        }
        return try {
            Pair(PdfRenderer(descriptor), descriptor)
        } catch (e: SecurityException) {
            descriptor.close()
            null
        } catch (e: Exception) {
            descriptor.close()
            throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF could not be read", e)
        }
    }

    private fun discard(sink: OutputSink, names: List<String>) {
        for (name in names) {
            try { sink.delete(name) } catch (e: Exception) { /* best effort */ }
        }
    }
}
