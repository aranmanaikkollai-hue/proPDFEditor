package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.util.LruCache
import java.util.LinkedList

/**
 * Bitmap pool for reusing bitmap memory across page renders.
 * Prevents GC pressure and OutOfMemoryError on large PDFs.
 * Thread-safe.
 */
class BitmapPool(maxMemoryBytes: Int = calculateDefaultMaxSize()) {

    private val lock = Object()

    /** Pool keyed by "widthxheight" */
    private val pool = LruCache<String, LinkedList<Bitmap>>(maxMemoryBytes) {
        override fun sizeOf(key: String, value: LinkedList<Bitmap>): Int {
            val sample = value.firstOrNull() ?: return 0
            return sample.byteCount * value.size
        }
    }

    /** Active bitmaps currently loaned out (for leak detection) */
    private val activeBitmaps = mutableSetOf<Bitmap>()

    /**
     * Obtain a bitmap of the requested size.
     * Reuses from pool if available, otherwise creates new.
     */
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

    /**
     * Return a bitmap to the pool for reuse.
     * Safe to call multiple times; idempotent.
     */
    fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val key = "${bitmap.width}x${bitmap.height}"
        synchronized(lock) {
            if (!activeBitmaps.remove(bitmap)) return // Already recycled or not from this pool
            val list = pool.get(key) ?: LinkedList<Bitmap>().also { pool.put(key, it) }
            // Limit list size per dimension to prevent unbounded growth
            if (list.size < MAX_PER_DIMENSION) {
                list.addLast(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    /**
     * Recycle all pooled bitmaps. Call when PDF is closed.
     */
    fun evictAll() {
        synchronized(lock) {
            activeBitmaps.forEach { try { it.recycle() } catch (_: Exception) {} }
            activeBitmaps.clear()
            pool.evictAll()
        }
    }

    /** Debug: number of bitmaps currently loaned out */
    fun activeCount(): Int = synchronized(lock) { activeBitmaps.size }

    companion object {
        private const val MAX_PER_DIMENSION = 3

        fun calculateDefaultMaxSize(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
        }
    }
}
