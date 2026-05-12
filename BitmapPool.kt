package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.util.LruCache
import java.util.LinkedList

class BitmapPool(maxMemoryBytes: Int = calculateDefaultMaxSize()) {

    private val lock = Object()
    private val pool = LruCache<String, LinkedList<Bitmap>>(maxMemoryBytes) {
        override fun sizeOf(key: String, value: LinkedList<Bitmap>): Int {
            val sample = value.firstOrNull() ?: return 0
            return sample.byteCount * value.size
        }
    }
    private val activeBitmaps = mutableSetOf<Bitmap>()

    fun obtain(width: Int, height: Int): Bitmap {
        val key = "${width}x${height}"
        synchronized(lock) {
            val list = pool.get(key)
            if (list != null && list.isNotEmpty()) {
                val bmp = list.removeFirst()
                if (!bmp.isRecycled) {
                    activeBitmaps.add(bmp)
                    return bmp
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            synchronized(lock) { activeBitmaps.add(it) }
        }
    }

    fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val key = "${bitmap.width}x${bitmap.height}"
        synchronized(lock) {
            if (!activeBitmaps.remove(bitmap)) return
            val list = pool.get(key) ?: LinkedList<Bitmap>().also { pool.put(key, it) }
            if (list.size < MAX_PER_DIMENSION) {
                list.addLast(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    fun evictAll() {
        synchronized(lock) {
            activeBitmaps.forEach { try { it.recycle() } catch (_: Exception) {} }
            activeBitmaps.clear()
            pool.evictAll()
        }
    }

    fun activeCount(): Int = synchronized(lock) { activeBitmaps.size }

    companion object {
        private const val MAX_PER_DIMENSION = 3
        fun calculateDefaultMaxSize(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
        }
    }
}
