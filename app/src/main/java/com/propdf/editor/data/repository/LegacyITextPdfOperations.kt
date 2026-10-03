package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.AnnotationStroke
import com.propdf.core.domain.model.AnnotationText
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppResult
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.utils.FileHelper
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * REMAINING iText code from the former PdfOperationsRepositoryImpl / PdfOperationsManager.
 *
 * Phase 2 (Page Editor -> PDFBox) moved every Page Editor operation to [PdfBoxPageEngine].
 * What is left here is NOT part of the Page Editor path and is intentionally unchanged,
 * to be migrated in its own phase:
 *   - compress / compressPdf / optimize (Compress tool)
 * (encrypt / decrypt / removePdfPassword moved to PDFBox in Phase 3)
 *   - saveAnnotations (annotation export)
 * Code is moved verbatim; do not extend. Delete this class when the iText dependency is removed.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
internal class LegacyITextPdfOperations(
    private val context: Context,
    private val dispatchers: DispatcherProvider? = null,
    private val logger: AppLogger? = null
) {
    private val ioDispatcher get() = dispatchers?.io ?: kotlinx.coroutines.Dispatchers.IO

    companion object {
        private const val TAG = "PdfOps"
    }

    // ===================== COMPRESS =====================
    suspend fun compress(
        inputFile: File,
        outputFile: File,
        config: CompressConfig
    ): AppResult<File> = withContext(ioDispatcher) {
        runCatching {
            val props = WriterProperties().apply {
                setCompressionLevel(config.level.coerceIn(1, 9))
                setFullCompressionMode(true)
                useSmartMode()
            }
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath, props))
            try { } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== SAVE ANNOTATIONS =====================
    suspend fun saveAnnotations(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationStroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<AnnotationText>, Float>>
    ): AppResult<File> = withContext(ioDispatcher) {
        runCatching {
            val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfReader(inputFile.absolutePath), PdfWriter(outputFile.absolutePath))
            try {
                val allPages = (pageAnnotations.keys + pageTextAnnotations.keys).toSet()
                for (idx in allPages) {
                    val pdfPageNum = idx + 1
                    if (pdfPageNum > doc.numberOfPages) continue
                    val pdfPage = doc.getPage(pdfPageNum)
                    val pdfH = pdfPage.pageSize.height
                    val canvas = PdfCanvas(pdfPage.newContentStreamAfter(), pdfPage.resources, doc)
                    try {
                        // Draw strokes
                        pageAnnotations[idx]?.let { (strokes, scale) ->
                            for (stroke in strokes) {
                                if (stroke.tool == "eraser") continue
                                val r = android.graphics.Color.red(stroke.color) / 255f
                                val g = android.graphics.Color.green(stroke.color) / 255f
                                val b = android.graphics.Color.blue(stroke.color) / 255f
                                val a = (android.graphics.Color.alpha(stroke.color) / 255f).coerceIn(0f, 1f)
                                canvas.saveState()
                                canvas.setExtGState(PdfExtGState().apply { strokeOpacity = a; fillOpacity = a })
                                canvas.setStrokeColor(DeviceRgb(r, g, b))
                                canvas.setLineWidth((stroke.strokeWidth / scale).coerceAtLeast(0.5f))
                                canvas.setLineCapStyle(1)
                                canvas.setLineJoinStyle(1)
                                // Draw path from points
                                if (stroke.pathData.isNotEmpty()) {
                                    val first = stroke.pathData.first()
                                    canvas.moveTo((first.x / scale).toDouble(), (pdfH - first.y / scale).toDouble())
                                    stroke.pathData.drop(1).forEach { pt ->
                                        canvas.lineTo((pt.x / scale).toDouble(), (pdfH - pt.y / scale).toDouble())
                                    }
                                }
                                canvas.stroke()
                                canvas.restoreState()
                            }
                        }
                        // Draw text annotations
                        pageTextAnnotations[idx]?.let { (textAnnots, scale) ->
                            for (ta in textAnnots) {
                                val bmp = renderTextBitmap(ta.text, ta.color, ta.sizePx)
                                val pngBytes = bitmapToPng(bmp)
                                bmp.recycle()
                                val imgData = ImageDataFactory.create(pngBytes)
                                val bW = imgData.width.toFloat()
                                val bH = imgData.height.toFloat()
                                val pdfX = ta.x / scale
                                val pdfY = pdfH - (ta.y / scale) - (bH / scale)
                                canvas.saveState()
                                canvas.addXObjectWithTransformationMatrix(
                                    com.itextpdf.kernel.pdf.xobject.PdfImageXObject(imgData),
                                    bW / scale, 0f, 0f, bH / scale, pdfX, pdfY
                                )
                                canvas.restoreState()
                            }
                        }
                    } finally { canvas.release() }
                }
            } finally { doc.close() }
            outputFile
        }.toAppResult()
    }

    // ===================== moved from PdfOperationsManager =====================
    suspend fun compressPdf(file: File, output: File, level: Int = 6): Result<File> = withContext(ThreadPoolManager.BackgroundDispatcher) {
        runCatching {
            // Step 1: recompress embedded images (PDFBox). This is the part that
            // actually matters for scanned/image-heavy PDFs --- the structural
            // pass below (step 2, unchanged) barely touches image size at all.
            // Ported from :viewer's PdfToolEngine.compressPdf, which was fully
            // built but never reachable from any live screen. Adapted here to
            // work on plain Files (this manager's existing convention) instead
            // of SAF Uris, and the original's reflection-based AcroForm removal
            // was deliberately dropped --- it silently destroyed all PDF form
            // fields as an undocumented side effect, which is a bug, not a
            // feature worth preserving.
            val stage1Dir = output.parentFile ?: output.absoluteFile.parentFile
            val stage1 = File(stage1Dir, "${output.nameWithoutExtension}_stage1.pdf")
            try {
                recompressEmbeddedImages(file, stage1, level)
            } catch (e: Exception) {
                Log.w("PdfOperationsManager", "Image recompression skipped: ${e.message}")
                file.copyTo(stage1, overwrite = true)
            }

            // Step 2: existing structural stream-compression pass (unchanged).
            val reader = PdfReader(stage1)
            val writer = PdfWriter(output).apply {
                setCompressionLevel(level.coerceIn(0, 9))
            }
            val src = com.itextpdf.kernel.pdf.PdfDocument(reader)
            val dest = com.itextpdf.kernel.pdf.PdfDocument(writer)
            src.copyPagesTo(1, src.numberOfPages, dest)
            dest.close()
            src.close()
            writer.close()
            reader.close()
            stage1.delete()
            output
        }
    }

    /**
     * Recompresses embedded raster images in-place using PDFBox, ahead of the
     * iText7 structural pass. Level maps to how aggressively images are
     * recompressed:
     *  - level <= 3 ("High quality"): skipped entirely, to avoid any risk of
     *    bloating already-compressed images when the user picked the least
     *    aggressive option.
     *  - level 4-6 ("Medium"): moderate JPEG quality.
     *  - level >= 7 ("Maximum"): aggressive JPEG quality.
     * Small images (icons/logos, under ~100x100) are left untouched --- not
     * worth the recompression cost or quality loss.
     */
    private fun recompressEmbeddedImages(input: File, output: File, level: Int) {
        if (level <= 3) {
            input.copyTo(output, overwrite = true)
            return
        }
        val quality = if (level >= 7) 0.35f else 0.6f

        com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i)
                val resources = page.resources ?: continue
                val imageNames = resources.xObjectNames?.toList() ?: continue
                imageNames.forEach { name ->
                    val xObject = resources.getXObject(name)
                        as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
                        ?: return@forEach
                    try {
                        val bitmap = xObject.image ?: return@forEach
                        if (bitmap.width * bitmap.height < 10000) return@forEach
                        val recompressed = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
                            .createFromImage(doc, bitmap, quality)
                        resources.put(name, recompressed)
                    } catch (_: Exception) {
                        // Leave this particular image untouched if it can't be
                        // recompressed; don't fail the whole operation over one
                        // image.
                    }
                }
            }
            doc.save(output)
        }
    }

    private fun renderTextBitmap(text: String, color: Int, sizePx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx.coerceAtLeast(12f)
            typeface = Typeface.DEFAULT
            isLinearText = true
            isSubpixelText = true
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val w = (bounds.width() + sizePx).toInt().coerceAtLeast(4)
        val h = (bounds.height() + sizePx * 0.5f).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            drawText(text, sizePx * 0.2f, bounds.height().toFloat() + sizePx * 0.1f, paint)
        }
        return bmp
    }

    private fun bitmapToPng(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    private fun PdfCanvas.addXObjectWithTransformationMatrix(
        xobj: com.itextpdf.kernel.pdf.xobject.PdfXObject,
        a: Float, b: Float, c: Float, d: Float, e: Float, f: Float
    ): PdfCanvas {
        saveState()
        concatMatrix(a.toDouble(), b.toDouble(), c.toDouble(), d.toDouble(), e.toDouble(), f.toDouble())
        addXObjectAt(xobj, 0f, 0f)
        restoreState()
        return this
    }
}
