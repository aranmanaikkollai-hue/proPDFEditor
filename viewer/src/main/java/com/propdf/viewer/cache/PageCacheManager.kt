package com.propdf.viewer.cache

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe LRU cache for rendered PDF page bitmaps.
 * Dual-layer cache: full-resolution pages + low-res thumbnails.
 * Optimized for memory-constrained mobile devices with automatic eviction.
 */
class PageCacheManager(maxMemoryMB: Int = 64) {

    companion object {
        private const val DEFAULT_CACHE_SIZE_MB = 64
        private const val THUMBNAIL_CACHE_SIZE_MB = 16
        private const val BYTES_PER_MB = 1024 * 1024
    }

    private val maxSizeBytes = maxMemoryMB * BYTES_PER_MB

    private val pageCache = object : LruCache<Int, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: Bitmap?,
            newValue: Bitmap?
        ) {
            if (evicted && oldValue != null && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private val thumbnailCache = object : LruCache<Int, Bitmap>(THUMBNAIL_CACHE_SIZE_MB * BYTES_PER_MB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) {
            if (evicted && oldValue != null && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    private val preloadQueue = ConcurrentHashMap.newKeySet<Int>()
    private val cacheMutex = Mutex()

    suspend fun getPage(key: Int): Bitmap? = cacheMutex.withLock {
        pageCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun getThumbnail(key: Int): Bitmap? = cacheMutex.withLock {
        thumbnailCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun putPage(key: Int, bitmap: Bitmap) = cacheMutex.withLock {
        if (!bitmap.isRecycled) {
            pageCache.put(key, bitmap)
        }
    }

    suspend fun putThumbnail(key: Int, bitmap: Bitmap) = cacheMutex.withLock {
        if (!bitmap.isRecycled) {
            thumbnailCache.put(key, bitmap)
        }
    }

    fun isInPreloadQueue(key: Int): Boolean = preloadQueue.contains(key)

    fun addToPreloadQueue(key: Int) {
        preloadQueue.add(key)
    }

    fun removeFromPreloadQueue(key: Int) {
        preloadQueue.remove(key)
    }

    fun clearPages() {
        pageCache.evictAll()
        preloadQueue.clear()
    }

    fun clearThumbnails() {
        thumbnailCache.evictAll()
    }

    fun clearAll() {
        pageCache.evictAll()
        thumbnailCache.evictAll()
        preloadQueue.clear()
    }

    fun evictPage(key: Int) {
        pageCache.remove(key)
    }

    fun trimToSize(size: Int) {
        pageCache.trimToSize(size)
    }

    fun getCacheSize(): Int = pageCache.size()

    fun getMaxCacheSize(): Int = pageCache.maxSize()

    fun getThumbnailCacheSize(): Int = thumbnailCache.size()
}
