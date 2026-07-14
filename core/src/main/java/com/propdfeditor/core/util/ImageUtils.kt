package com.propdfeditor.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    
    fun saveBitmapWithBackground(bitmap: Bitmap, file: File, backgroundColor: Int) {
        FileOutputStream(file).use { out ->
            if (backgroundColor == Color.TRANSPARENT) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBitmap)
                canvas.drawColor(backgroundColor)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                newBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                newBitmap.recycle()
            }
        }
    }

    fun loadBitmap(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun processSignatureImage(file: File, targetBackground: Int) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        
        if (targetBackground == Color.TRANSPARENT) {
            // Make white background transparent
            val width = bitmap.width
            val height = bitmap.height
            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(newBitmap)
            
            val paint = Paint().apply {
                isAntiAlias = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            
            // Create alpha mask - white becomes transparent
            val alphaBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            val alphaCanvas = Canvas(alphaBitmap)
            val alphaPaint = Paint().apply {
                isAntiAlias = true
            }
            alphaCanvas.drawBitmap(bitmap, 0f, 0f, alphaPaint)
            
            // Invert: make white areas transparent
            val pixels = IntArray(width * height)
            alphaBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val gray = Color.red(pixels[i]) // R=G=B for grayscale
                if (gray > 240) {
                    pixels[i] = Color.TRANSPARENT
                } else {
                    pixels[i] = Color.argb(255 - gray + 20, 0, 0, 0)
                }
            }
            alphaBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            canvas.drawBitmap(alphaBitmap, 0f, 0f, paint)
            
            FileOutputStream(file).use { out ->
                newBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            bitmap.recycle()
            newBitmap.recycle()
            alphaBitmap.recycle()
        }
    }

    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val scale = minOf(
            maxWidth.toFloat() / width,
            maxHeight.toFloat() / height,
            1.0f
        )
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
