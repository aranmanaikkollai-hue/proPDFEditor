package com.propdf.pdfedit.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

class ImagesToPdfOptions(
    val pageMode: PageMode = PageMode.A4,
    /** Space around the picture on A4 and Letter pages, in points. */
    val marginPts: Double = 18.0,
    /** Resolution that gives the page size in MATCH_IMAGE mode. */
    val matchDpi: Int = 150,
    /** Pictures that are not JPEG are reduced to at most this many pixels. */
    val maxPixels: Long = 25_000_000L,
    val title: String? = null
)

/**
 * Builds one PDF with one page per image, in the order given. JPEG files are embedded as they are (no recompression) and a
 * JPEG's EXIF orientation is applied by the drawing matrix, so phone photos come out upright. Other formats (PNG, WebP, ...)
 * are decoded and stored losslessly, with transparency kept on a white page. The saved file is reopened and its page count
 * checked before it reaches the sink; on any failure or cancellation the output is removed. Inputs are only read.
 */
class ImagesToPdfConverter {

    suspend fun convert(
        images: List<File>,
        outputName: String,
        options: ImagesToPdfOptions,
        sink: OutputSink,
        tempDir: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ConvertReport = withContext(Dispatchers.IO) {
        if (images.isEmpty()) throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "No images selected")
        val name = OutputNaming.baseName(outputName) + ".pdf"
        try {
            tempDir.mkdirs()
            val warnings = LinkedHashSet<ConvertWarning>()
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)).use { doc ->
                for ((n, file) in images.withIndex()) {
                    ensureActive()
                    addImagePage(doc, file, options, warnings)
                    onProgress(n + 1, images.size)
                }
                val info = PDDocumentInformation()
                info.creator = "proPDFEditor"
                if (!options.title.isNullOrBlank()) info.title = options.title
                doc.documentInformation = info
                ensureActive()
                PdfPublisher.publish(doc, tempDir, images.size, name, sink)
            }
            ConvertReport(listOf(name), images.size, images.size, warnings)
        } catch (e: CancellationException) {
            discard(sink, name)
            throw e
        } catch (e: ConvertException) {
            discard(sink, name)
            throw e
        } catch (e: OutOfMemoryError) {
            discard(sink, name)
            throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory for these images", e)
        } catch (e: Exception) {
            discard(sink, name)
            throw ConvertException(ConvertException.Reason.WRITE_FAILED, "The PDF could not be created", e)
        }
    }

    private fun addImagePage(doc: PDDocument, file: File, options: ImagesToPdfOptions, warnings: MutableSet<ConvertWarning>) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ConvertException(ConvertException.Reason.UNREADABLE, "This file is not a readable image: " + file.name)
        }
        val jpeg = isJpeg(file)
        val orientation = if (jpeg) readOrientation(file) else 1
        val (displayW, displayH) = ExifOrientation.displaySize(orientation, bounds.outWidth, bounds.outHeight)
        val plan = ImagePagePlanner.plan(options.pageMode, displayW, displayH, options.marginPts, options.matchDpi)
        val image = createImage(doc, file, jpeg, bounds, options, warnings)
        val page = PDPage(PDRectangle(plan.pageWidth.toFloat(), plan.pageHeight.toFloat()))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            val m = ExifOrientation.matrixFor(orientation, plan.rect)
            cs.drawImage(image, Matrix(m.a.toFloat(), m.b.toFloat(), m.c.toFloat(), m.d.toFloat(), m.e.toFloat(), m.f.toFloat()))
        }
    }

    private fun createImage(
        doc: PDDocument,
        file: File,
        jpeg: Boolean,
        bounds: BitmapFactory.Options,
        options: ImagesToPdfOptions,
        warnings: MutableSet<ConvertWarning>
    ): PDImageXObject {
        if (jpeg) {
            try {
                return FileInputStream(file).use { JPEGFactory.createFromStream(doc, it) }
            } catch (e: Exception) {
                // for example a CMYK JPEG: decode it below instead
            }
        }
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > max(1L, options.maxPixels)) sample *= 2
        if (sample > 1) warnings.add(ConvertWarning.IMAGE_DOWNSAMPLED)
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap? = try {
            BitmapFactory.decodeFile(file.path, decode)
        } catch (e: OutOfMemoryError) {
            throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory for " + file.name, e)
        }
        val bitmap: Bitmap = decoded
            ?: throw ConvertException(ConvertException.Reason.UNREADABLE, "This file is not a readable image: " + file.name)
        return try {
            LosslessFactory.createFromImage(doc, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun isJpeg(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(3)
            input.read(head) == 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        }
    } catch (e: Exception) {
        false
    }

    private fun readOrientation(file: File): Int = try {
        ExifOrientation.normalize(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
    } catch (e: Exception) {
        1
    }

    private fun discard(sink: OutputSink, name: String) {
        try { sink.delete(name) } catch (e: Exception) { /* best effort */ }
    }
}
