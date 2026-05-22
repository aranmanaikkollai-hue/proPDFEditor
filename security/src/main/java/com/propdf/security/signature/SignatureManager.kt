package com.propdf.security.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.withSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Professional signature management system.
 * Handles: draw signatures, image signatures, storage, export/import, touch smoothing.
 */
class SignatureManager(private val context: Context) {

    private val signaturesDir = File(context.filesDir, "signatures").apply { mkdirs() }

    companion object {
        private const val STROKE_WIDTH = 4f
        private const val SMOOTHING_FACTOR = 0.3f
        private const val SIGNATURE_WIDTH = 600
        private const val SIGNATURE_HEIGHT = 300
    }

    // ==================== DRAW SIGNATURE ====================

    /**
     * Create a signature from touch points with smoothing.
     */
    fun createSignatureFromPoints(points: List<PointF>): Bitmap {
        val bitmap = Bitmap.createBitmap(SIGNATURE_WIDTH, SIGNATURE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        if (points.size < 2) return bitmap

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        val smoothed = smoothPoints(points)

        path.moveTo(smoothed[0].x, smoothed[0].y)
        for (i in 1 until smoothed.size) {
            val prev = smoothed[i - 1]
            val curr = smoothed[i]
            val midX = (prev.x + curr.x) / 2
            val midY = (prev.y + curr.y) / 2
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        path.lineTo(smoothed.last().x, smoothed.last().y)

        canvas.drawPath(path, paint)
        return bitmap
    }

    /**
     * Smooth touch points using moving average.
     */
    private fun smoothPoints(points: List<PointF>): List<PointF> {
        if (points.size < 3) return points
        val smoothed = mutableListOf<PointF>()
        smoothed.add(points[0])
        for (i in 1 until points.size - 1) {
            val x = points[i].x * (1 - SMOOTHING_FACTOR) + (points[i - 1].x + points[i + 1].x) / 2 * SMOOTHING_FACTOR
            val y = points[i].y * (1 - SMOOTHING_FACTOR) + (points[i - 1].y + points[i + 1].y) / 2 * SMOOTHING_FACTOR
            smoothed.add(PointF(x.toFloat(), y.toFloat()))
        }
        smoothed.add(points.last())
        return smoothed
    }

    // ==================== IMAGE SIGNATURE ====================

    /**
     * Convert an image to a transparent signature (white bg → transparent).
     */
    suspend fun imageToSignature(uri: Uri): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()
            bitmap ?: return@withContext Result.failure(Exception("Cannot decode image"))

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.TRANSPARENT)

            // Remove white background, keep dark pixels
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val brightness = (r + g + b) / 3
                if (brightness < 240) {
                    pixels[i] = Color.argb(255, r, g, b)
                } else {
                    pixels[i] = Color.TRANSPARENT
                }
            }

            result.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== STORAGE ====================

    /**
     * Save a signature to persistent storage.
     */
    suspend fun saveSignature(bitmap: Bitmap, name: String? = null): Result<SignatureEntry> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val fileName = name ?: "signature_$id"
            val file = File(signaturesDir, "$fileName.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val entry = SignatureEntry(
                id = id,
                name = fileName,
                filePath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
                width = bitmap.width,
                height = bitmap.height
            )
            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load all saved signatures.
     */
    fun loadSignatures(): List<SignatureEntry> {
        return signaturesDir.listFiles()?.filter { it.extension == "png" }?.map { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            SignatureEntry(
                id = file.nameWithoutExtension,
                name = file.nameWithoutExtension,
                filePath = file.absolutePath,
                createdAt = file.lastModified(),
                width = bitmap?.width ?: 0,
                height = bitmap?.height ?: 0
            )
        } ?: emptyList()
    }

    /**
     * Delete a signature.
     */
    fun deleteSignature(id: String): Boolean {
        val file = File(signaturesDir, "$id.png")
        return file.delete()
    }

    /**
     * Load a signature bitmap by ID.
     */
    fun loadSignatureBitmap(id: String): Bitmap? {
        val file = File(signaturesDir, "$id.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    // ==================== EXPORT/IMPORT ====================

    /**
     * Export signature as PNG bytes.
     */
    fun exportSignature(id: String): ByteArray? {
        val bitmap = loadSignatureBitmap(id) ?: return null
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * Import signature from PNG bytes.
     */
    suspend fun importSignature(data: ByteArray, name: String): Result<SignatureEntry> = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return@withContext Result.failure(Exception("Invalid PNG data"))
            saveSignature(bitmap, name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== SIGNATURE ON PDF ====================

    /**
     * Place a signature on a PDF page at specified coordinates.
     */
    fun placeSignatureOnBitmap(
        pageBitmap: Bitmap,
        signature: Bitmap,
        x: Float,
        y: Float,
        scale: Float = 1f,
        rotation: Float = 0f
    ): Bitmap {
        val result = pageBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        canvas.withSave {
            translate(x, y)
            rotate(rotation)
            scale(scale, scale)
            drawBitmap(signature, 0f, 0f, null)
        }

        return result
    }
}

data class SignatureEntry(
    val id: String,
    val name: String,
    val filePath: String,
    val createdAt: Long,
    val width: Int,
    val height: Int
)
