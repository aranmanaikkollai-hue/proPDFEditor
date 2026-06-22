package com.propdf.scanner.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.withSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentScannerEngine @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val TAG = "DocScannerEngine"
        private const val MAX_PROCESS_DIMENSION = 2048
    }

    suspend fun scanDocument(
        sourceBitmap: Bitmap,
        options: ScanOptions = ScanOptions()
    ): Result<ScannedDocument> = withContext(Dispatchers.Default) {
        try {
            val scaledBitmap = scaleForProcessing(sourceBitmap)
            if (!isActive) return@withContext Result.failure(CancellationException())

            val corners = detectDocumentCorners(scaledBitmap)
            if (!isActive) return@withContext Result.failure(CancellationException())

            val corrected = if (corners != null && options.autoCorrect) {
                perspectiveCorrect(scaledBitmap, corners)
            } else {
                scaledBitmap
            }
            if (!isActive) return@withContext Result.failure(CancellationException())

            val shadowRemoved = if (options.removeShadows) {
                removeShadows(corrected)
            } else {
                corrected
            }
            if (!isActive) return@withContext Result.failure(CancellationException())

            val filtered = applyFilter(shadowRemoved, options.colorMode)
            if (!isActive) return@withContext Result.failure(CancellationException())

            val enhanced = if (options.autoEnhance) {
                autoEnhance(filtered)
            } else {
                filtered
            }

            Result.success(
                ScannedDocument(
                    bitmap = enhanced,
                    originalBitmap = sourceBitmap,
                    detectedCorners = corners,
                    processingTimeMs = 0L
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun detectDocumentCorners(bitmap: Bitmap): List<PointF>? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        val edges = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                gray[y * w + x] = ((0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8) and 0xFF) +
                        0.114f * (c and 0xFF)).toInt())
            }
        }

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (-gray[(y - 1) * w + (x - 1)] - 2 * gray[y * w + (x - 1)] - gray[(y + 1) * w + (x - 1)] +
                        gray[(y - 1) * w + (x + 1)] + 2 * gray[y * w + (x + 1)] + gray[(y + 1) * w + (x + 1)])
                val gy = (-gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)] +
                        gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)])
                edges[y * w + x] = sqrt((gx * gx + gy * gy).toFloat())
            }
        }

        return findLargestQuad(edges, w, h)
    }

    fun perspectiveCorrect(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        val src = floatArrayOf(
            corners[0].x, corners[0].y,
            corners[1].x, corners[1].y,
            corners[2].x, corners[2].y,
            corners[3].x, corners[3].y
        )

        val width = ((distance(corners[0], corners[1]) + distance(corners[2], corners[3])) / 2).toInt()
        val height = ((distance(corners[0], corners[3]) + distance(corners[1], corners[2])) / 2).toInt()
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        val dst = floatArrayOf(
            0f, 0f,
            safeWidth.toFloat(), 0f,
            safeWidth.toFloat(), safeHeight.toFloat(),
            0f, safeHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun removeShadows(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val outPixels = IntArray(w * h)
        val blockSize = 32

        for (blockY in 0 until h step blockSize) {
            for (blockX in 0 until w step blockSize) {
                val xEnd = min(blockX + blockSize, w)
                val yEnd = min(blockY + blockSize, h)

                var localMaxL = 0f
                for (y in blockY until yEnd) {
                    for (x in blockX until xEnd) {
                        val c = pixels[y * w + x]
                        val l = maxOf(((c shr 16) and 0xFF), ((c shr 8) and 0xFF), (c and 0xFF))
                        if (l > localMaxL) localMaxL = l.toFloat()
                    }
                }

                for (y in blockY until yEnd) {
                    for (x in blockX until xEnd) {
                        val idx = y * w + x
                        val c = pixels[idx]
                        val r = ((c shr 16) and 0xFF)
                        val g = ((c shr 8) and 0xFF)
                        val b = (c and 0xFF)

                        val factor = 255f / (localMaxL.coerceAtLeast(1f))
                        val nr = (r * factor).toInt().coerceIn(0, 255)
                        val ng = (g * factor).toInt().coerceIn(0, 255)
                        val nb = (b * factor).toInt().coerceIn(0, 255)

                        outPixels[idx] = Color.argb(0xFF, nr, ng, nb)
                    }
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    fun applyFilter(bitmap: Bitmap, mode: ColorMode): Bitmap {
        return when (mode) {
            ColorMode.AUTO -> bitmap
            ColorMode.COLOR -> bitmap
            ColorMode.GRAYSCALE -> applyColorMatrix(bitmap, ColorMatrix().apply { setSaturation(0f) })
            ColorMode.BLACK_WHITE -> applyBlackAndWhite(bitmap)
            ColorMode.SEPIA -> applyColorMatrix(bitmap, ColorMatrix().apply {
                set(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
            ColorMode.HIGH_CONTRAST -> applyColorMatrix(bitmap, ColorMatrix().apply {
                set(floatArrayOf(
                    2f, 0f, 0f, 0f, -128f,
                    0f, 2f, 0f, 0f, -128f,
                    0f, 0f, 2f, 0f, -128f,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
        }
    }

    fun autoEnhance(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0

        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }

        val rangeR = (maxR - minR).coerceAtLeast(1)
        val rangeG = (maxG - minG).coerceAtLeast(1)
        val rangeB = (maxB - minB).coerceAtLeast(1)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((((c shr 16) and 0xFF) - minR) * 255) / rangeR).coerceIn(0, 255)
            val g = (((((c shr 8) and 0xFF) - minG) * 255) / rangeG).coerceIn(0, 255)
            val b = ((((c and 0xFF) - minB) * 255) / rangeB).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun flip(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            else postScale(1f, -1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun adjustBrightness(bitmap: Bitmap, delta: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun scaleForProcessing(bitmap: Bitmap): Bitmap {
        val maxDim = max(bitmap.width, bitmap.height)
        if (maxDim <= MAX_PROCESS_DIMENSION) return bitmap
        val scale = MAX_PROCESS_DIMENSION.toFloat() / maxDim
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun findLargestQuad(edges: FloatArray, w: Int, h: Int): List<PointF>? {
        val margin = (min(w, h) * 0.05f).toInt()

        val topY = findEdgeRow(edges, w, h, margin, true)
        val bottomY = findEdgeRow(edges, w, h, margin, false)
        val leftX = findEdgeCol(edges, w, h, margin, true)
        val rightX = findEdgeCol(edges, w, h, margin, false)

        if (topY == null || bottomY == null || leftX == null || rightX == null) return null
        if (rightX - leftX < w * 0.3 || bottomY - topY < h * 0.3) return null

        return listOf(
            PointF(leftX.toFloat(), topY.toFloat()),
            PointF(rightX.toFloat(), topY.toFloat()),
            PointF(rightX.toFloat(), bottomY.toFloat()),
            PointF(leftX.toFloat(), bottomY.toFloat())
        )
    }

    private fun findEdgeRow(edges: FloatArray, w: Int, h: Int, margin: Int, fromTop: Boolean): Int? {
        val range = if (fromTop) (margin until h / 2) else ((h - margin - 1) downTo h / 2)
        for (y in range) {
            var sum = 0f
            for (x in margin until w - margin) {
                sum += edges[y * w + x]
            }
            if (sum > (w - 2 * margin) * 50) return y
        }
        return null
    }

    private fun findEdgeCol(edges: FloatArray, w: Int, h: Int, margin: Int, fromLeft: Boolean): Int? {
        val range = if (fromLeft) (margin until w / 2) else ((w - margin - 1) downTo w / 2)
        for (x in range) {
            var sum = 0f
            for (y in margin until h - margin) {
                sum += edges[y * w + x]
            }
            if (sum > (h - 2 * margin) * 50) return x
        }
        return null
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        return sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
    }

    private fun applyColorMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).withSave {
            drawBitmap(bitmap, 0f, 0f, Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
        }
        return out
    }

    private fun applyBlackAndWhite(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val l = ((0.299 * ((c shr 16) and 0xFF) +
                    0.587 * ((c shr 8) and 0xFF) +
                    0.114 * (c and 0xFF)).toInt())
            val bw = if (l > 128) 0xFF else 0x00
            pixels[i] = Color.argb(0xFF, bw, bw, bw)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    class CancellationException : Exception("Scan cancelled")
}

data class ScannedDocument(
    val bitmap: Bitmap,
    val originalBitmap: Bitmap,
    val detectedCorners: List<PointF>?,
    val processingTimeMs: Long
)

data class ScanOptions(
    val autoCorrect: Boolean = true,
    val removeShadows: Boolean = true,
    val autoEnhance: Boolean = true,
    val colorMode: ColorMode = ColorMode.AUTO
)

enum class ColorMode {
    AUTO, COLOR, GRAYSCALE, BLACK_WHITE, SEPIA, HIGH_CONTRAST
}
