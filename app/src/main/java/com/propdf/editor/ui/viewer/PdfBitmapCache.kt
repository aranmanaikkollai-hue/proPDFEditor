package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Bounded, memory-size-aware LRU cache for rendered page bitmaps.
 *
 * The previous viewer cached every rendered page bitmap forever in a plain map, which grows
 * without bound on large documents and is a direct path to OutOfMemoryError. This cache is
 * sized in bytes (not entry count) and automatically recycles evicted bitmaps.
 */
class PdfBitmapCache(maxBytes: Int) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun containsFresh(key: String): Boolean = get(key) != null

    fun clear() {
        cache.evictAll()
    }

    companion object {
        /** Key that also encodes the render width, so re-rendering at a different zoom level doesn't reuse a stale-resolution bitmap. */
        fun key(pageIndex: Int, targetWidthPx: Int): String = "$pageIndex@$targetWidthPx"
    }
}
