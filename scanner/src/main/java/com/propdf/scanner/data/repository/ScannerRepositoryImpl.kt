package com.propdf.scanner.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.ExportConfig
import com.propdf.core.domain.model.ExportFormat
import com.propdf.core.domain.model.ScannedPage
import com.propdf.core.domain.repository.ScannerRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppResult
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerRepositoryImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : ScannerRepository {

    companion object {
        private const val TAG = "ScannerRepo"
    }

    override suspend fun processCapturedImage(bitmap: Bitmap, colorMode: String): AppResult<Bitmap> =
        withContext(dispatchers.default) {
            runCatching {
                val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val totalPixels = out.width * out.height
                if (totalPixels <= 0) return@runCatching out
                val pixels = IntArray(totalPixels)
                out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)

                fun lum(c: Int) = ((0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()).coerceIn(0, 255)

                when (colorMode) {
                    "gray" -> for (i in pixels.indices) { val l = lum(pixels[i]); pixels[i] = Color.argb(0xFF, l, l, l) }
                    "bw" -> for (i in pixels.indices) { val bw = if (lum(pixels[i]) > 128) 0xFF else 0x00; pixels[i] = Color.argb(0xFF, bw, bw, bw) }
                    "auto" -> {
                        var minL = 255; var maxL = 0
                        for (p in pixels) { val l = lum(p); if (l < minL) minL = l; if (l > maxL) maxL = l }
                        val range = (maxL - minL).coerceAtLeast(1)
                        for (i in pixels.indices) {
                            val c = pixels[i]
                            val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                            val g = ((((c shr 8) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                            val b = (((c and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                            pixels[i] = Color.argb(0xFF, r, g, b)
                        }
                    }
                }
                out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
                out
            }.toAppResult()
        }

    override suspend fun applyFilter(bitmap: Bitmap, filterType: String): AppResult<Bitmap> =
        withContext(dispatchers.default) {
            runCatching {
                val src = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                when (filterType) {
                    "grayscale" -> applyMatrix(src, ColorMatrix().apply { setSaturation(0f) })
                    "bw" -> {
                        val pixels = IntArray(src.width * src.height)
                        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
                        for (i in pixels.indices) {
                            val l = ((0.299 * ((pixels[i] shr 16) and 0xFF) + 0.587 * ((pixels[i] shr 8) and 0xFF) + 0.114 * (pixels[i] and 0xFF)).toInt())
                            val bw = if (l > 128) 0xFF else 0x00
                            pixels[i] = Color.argb(0xFF, bw, bw, bw)
                        }
                        src.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
                        src
                    }
                    "sepia" -> applyMatrix(src, ColorMatrix().apply {
                        set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f))
                    })
                    "high_contrast" -> applyMatrix(src, ColorMatrix().apply {
                        set(floatArrayOf(2f,0f,0f,0f,-128f, 0f,2f,0f,0f,-128f, 0f,0f,2f,0f,-128f, 0f,0f,0f,1f,0f))
                    })
                    else -> src
                }
            }.toAppResult()
        }

    override suspend fun applyBrightness(bitmap: Bitmap, delta: Int): AppResult<Bitmap> =
        withContext(dispatchers.default) {
            runCatching {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
                    val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
                    val b = ((c and 0xFF) + delta).coerceIn(0, 255)
                    pixels[i] = Color.argb(0xFF, r, g, b)
                }
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap
            }.toAppResult()
        }

    override suspend fun rotate(bitmap: Bitmap, degrees: Float): AppResult<Bitmap> =
        withContext(dispatchers.default) {
            runCatching {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }.toAppResult()
        }

    override suspend fun saveAsPdf(
        context: Context,
        pages: List<ScannedPage>,
        config: ExportConfig
    ): AppResult<Uri> = withContext(dispatchers.io) {
        runCatching {
            val doc = android.graphics.pdf.PdfDocument()
            try {
                pages.forEachIndexed { i, page ->
                    val bmpOrig = page.bitmap
                    val bmp = if (config.pageWidthPt > 0 && config.pageHeightPt > 0) {
                        Bitmap.createScaledBitmap(bmpOrig,
                            config.pageWidthPt.toInt().coerceAtLeast(1),
                            config.pageHeightPt.toInt().coerceAtLeast(1), true)
                    } else bmpOrig
                    val pageW = if (config.pageWidthPt > 0) config.pageWidthPt.toInt() else bmp.width
                    val pageH = if (config.pageHeightPt > 0) config.pageHeightPt.toInt() else bmp.height
                    val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                    val pdfPage = doc.startPage(pi)
                    val matrix = android.graphics.Matrix()
                    if (config.pageWidthPt > 0 || config.pageHeightPt > 0) {
                        matrix.setScale(pageW.toFloat() / bmp.width, pageH.toFloat() / bmp.height)
                    }
                    pdfPage.canvas.drawBitmap(bmp, matrix, null)
                    doc.finishPage(pdfPage)
                    if (config.pageWidthPt > 0 && config.pageHeightPt > 0) bmp.recycle()
                }
                val fileName = "Scan_${System.currentTimeMillis()}.pdf"
                var outUri: Uri? = null
                val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                    }
                    outUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    outUri?.let { context.contentResolver.openOutputStream(it) }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                    val outFile = File(dir, fileName)
                    outUri = Uri.fromFile(outFile)
                    FileOutputStream(outFile)
                }
                if (out == null) throw IllegalStateException("Cannot create output stream")
                out.use { doc.writeTo(it) }
                outUri ?: throw IllegalStateException("No output URI")
            } finally { doc.close() }
        }.toAppResult()
    }

    override suspend fun saveAsJpegs(
        context: Context,
        pages: List<ScannedPage>,
        quality: Int
    ): AppResult<List<Uri>> = withContext(dispatchers.io) {
        runCatching {
            val uris = mutableListOf<Uri>()
            pages.forEachIndexed { i, page ->
                val bmp = page.bitmap
                val fileName = "Scan_${System.currentTimeMillis()}_page${i + 1}.jpg"
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                    }
                    val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    u?.let { context.contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, out) } }
                    u
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                    val f = File(dir, fileName)
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    Uri.fromFile(f)
                }
                uri?.let { uris.add(it) }
            }
            uris
        }.toAppResult()
    }

    override fun release() {
        // Nothing to release
    }

    private fun applyMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }
}
