package com.propdf.viewer.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe two-tier cache for rendered PDF pages and thumbnails.
 *
 * Full pages stay memory-only to avoid large disk churn. Thumbnails use memory
 * plus a bounded disk cache so the thumbnail rail can be reopened without
 * forcing every page to be re-rendered.
 */
class PageCacheManager(
    context: Context? = null,
    maxMemoryMB: Int = DEFAULT_CACHE_SIZE_MB,
    lowRamMode: Boolean = false
) {

    companion object {
        private const val DEFAULT_CACHE_SIZE_MB = 64
        private const val THUMBNAIL_CACHE_SIZE_MB = 16
        private const val LOW_RAM_PAGE_CACHE_MB = 24
        private const val LOW_RAM_THUMBNAIL_CACHE_MB = 6
        private const val DISK_THUMBNAIL_CACHE_MB = 48L
        private const val LOW_RAM_DISK_THUMBNAIL_CACHE_MB = 16L
        private const val BYTES_PER_MB = 1024 * 1024
        private const val THUMBNAIL_QUALITY = 82
        private const val THUMBNAIL_DIR = "viewer_thumbnails"
    }

    private val pageCacheBytes = ((if (lowRamMode) LOW_RAM_PAGE_CACHE_MB else maxMemoryMB) * BYTES_PER_MB)
        .coerceAtLeast(4 * BYTES_PER_MB)
    private val thumbnailCacheBytes = ((if (lowRamMode) LOW_RAM_THUMBNAIL_CACHE_MB else THUMBNAIL_CACHE_SIZE_MB) * BYTES_PER_MB)
        .coerceAtLeast(2 * BYTES_PER_MB)
    private val maxDiskCacheBytes = (if (lowRamMode) LOW_RAM_DISK_THUMBNAIL_CACHE_MB else DISK_THUMBNAIL_CACHE_MB) * BYTES_PER_MB
    private val thumbnailDiskDir = context?.let { File(it.cacheDir, THUMBNAIL_DIR).apply { mkdirs() } }

    private val pageCache = object : LruCache<Int, Bitmap>(pageCacheBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) {
            // Bitmaps are also handed to ImageView/PageView instances. Do not recycle here;
            // dropping the cache reference is enough and avoids drawing a recycled bitmap.
        }
    }

    private val thumbnailCache = object : LruCache<Int, Bitmap>(thumbnailCacheBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap?, newValue: Bitmap?) {
            // Thumbnail views can outlive cache entries while RecyclerView animations run.
            // Avoid explicit recycle of shared bitmaps.
        }
    }

    private val preloadQueue = ConcurrentHashMap.newKeySet<Int>()
    private val cacheMutex = Mutex()
    private val diskMutex = Mutex()

    suspend fun getPage(key: Int): Bitmap? = cacheMutex.withLock {
        pageCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun getThumbnail(key: Int): Bitmap? {
        cacheMutex.withLock {
            thumbnailCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        }

        val fromDisk = readThumbnailFromDisk(key) ?: return null
        cacheMutex.withLock { thumbnailCache.put(key, fromDisk) }
        return fromDisk
    }

    suspend fun putPage(key: Int, bitmap: Bitmap) = cacheMutex.withLock {
        if (!bitmap.isRecycled) {
            pageCache.put(key, bitmap)
        }
    }

    suspend fun putThumbnail(key: Int, bitmap: Bitmap) {
        cacheMutex.withLock {
            if (!bitmap.isRecycled) {
                thumbnailCache.put(key, bitmap)
            }
        }
        writeThumbnailToDisk(key, bitmap)
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
        clearDiskThumbnails()
    }

    fun clearAll() {
        pageCache.evictAll()
        thumbnailCache.evictAll()
        preloadQueue.clear()
        clearDiskThumbnails()
    }

    fun evictPage(key: Int) {
        pageCache.remove(key)
    }

    fun trimToSize(size: Int) {
        pageCache.trimToSize(size)
    }

    fun trimMemoryForBackground() {
        pageCache.trimToSize(pageCacheBytes / 4)
        thumbnailCache.trimToSize(thumbnailCacheBytes / 2)
        preloadQueue.clear()
    }

    fun getCacheSize(): Int = pageCache.size()

    fun getMaxCacheSize(): Int = pageCache.maxSize()

    fun getThumbnailCacheSize(): Int = thumbnailCache.size()

    private suspend fun readThumbnailFromDisk(key: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = thumbnailDiskFile(key) ?: return@withContext null
        if (!file.exists() || file.length() <= 0L) return@withContext null
        diskMutex.withLock {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }

    private suspend fun writeThumbnailToDisk(key: Int, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext
        val file = thumbnailDiskFile(key) ?: return@withContext
        diskMutex.withLock {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
            }
            evictOldDiskEntriesLocked()
        }
    }

    private fun thumbnailDiskFile(key: Int): File? = thumbnailDiskDir?.let { dir -> File(dir, "$key.jpg") }

    private fun evictOldDiskEntriesLocked() {
        val dir = thumbnailDiskDir ?: return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var totalBytes = files.sumOf { it.length() }
        for (file in files) {
            if (totalBytes <= maxDiskCacheBytes) break
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
    }

    private fun clearDiskThumbnails() {
        thumbnailDiskDir?.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }
}
