package com.propdf.editor.data.repository

import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerProcessor @Inject constructor() {

    suspend fun toGrayscale(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        result
    }

    suspend fun toBinaryBlackWhite(bitmap: Bitmap, threshold: Int = 128): Bitmap = withContext(Dispatchers.Default) {
        val gray = toGrayscale(bitmap)
        val width = gray.width; val height = gray.height
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val l = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()
            pixels[i] = if (l > threshold) Color.WHITE else Color.BLACK
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        result
    }

    suspend fun enhanceDocument(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.4f, 0f, 0f, 0f, -20f,
                0f, 1.4f, 0f, 0f, -20f,
                0f, 0f, 1.4f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        result
    }

    fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
