package com.propdf.viewer.rendering

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.Log
import com.propdf.viewer.model.ThumbnailPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory-efficient thumbnail generation and caching for the sidebar navigation.
 *
 * Strategy:
 * - Fixed-size thumbnails (e.g., 120x160dp) regardless of page size
 * - LRU disk cache for thumbnails (stored in app's cache directory)
 * - In-memory cache limited to ~20 thumbnails to stay RAM-efficient
 * - Background generation with cancellation support
 * - Lazy loading: only generate thumbnails for visible range + buffer
 */
class ThumbnailManager(
    private val bitmapPool: BitmapPool,
    private val pdfRenderer: PdfRenderer,
    private val cacheDir: java.io.File
) {
    companion object {
        private const val TAG = "ThumbnailManager"
        private const val THUMB_WIDTH_DP = 120
        private const val THUMB_HEIGHT_DP = 160
        private const val IN_MEMORY_CACHE_SIZE = 20
        private const val MAX_CONCURRENT_GENERATION = 3
        private const val RENDER_SCALE = 0.3f // Lower quality for thumbnails is acceptable
    }

    /** In-memory thumbnail cache */
    private val memoryCache = ConcurrentHashMap<Int, Bitmap>(IN_MEMORY_CACHE_SIZE)

    /** Track which thumbnails are being generated */
    private val generationMutex = Mutex()
    private val generatingPages = mutableSetOf<Int>()

    /** Limit concurrent generation to avoid overwhelming the renderer */
    private val generationSemaphore = Semaphore(MAX_CONCURRENT_GENERATION)

    /** Thumbnail size in pixels (calculated from density) */
    private var thumbWidth = THUMB_WIDTH_DP * 2 // Default, updated on init
    private var thumbHeight = THUMB_HEIGHT_DP * 2

    private val _thumbnailState = MutableStateFlow<ThumbnailState>(ThumbnailState.Idle)
    val thumbnailState: StateFlow<ThumbnailState> = _thumbnailState.asStateFlow()

    /**
     * Initialize with device screen density.
     */
    fun initialize(density: Float) {
        thumbWidth = (THUMB_WIDTH_DP * density).toInt()
        thumbHeight = (THUMB_HEIGHT_DP * density).toInt()
    }

    /**
     * Get thumbnail for a specific page.
     * Returns from cache if available, otherwise generates on demand.
     */
    suspend fun getThumbnail(pageIndex: Int): Bitmap? = withContext(Dispatchers.Default) {
        if (!isActive) return@withContext null

        // Check memory cache
        memoryCache[pageIndex]?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        // Check disk cache
        loadFromDiskCache(pageIndex)?.let { diskCached ->
            memoryCache[pageIndex] = diskCached
            return@withContext diskCached
        }

        // Generate thumbnail
        generateThumbnail(pageIndex)
    }

    /**
     * Generate thumbnails for a range of pages as a Flow.
     * Used for the thumbnail sidebar to stream results.
     */
    fun generateThumbnailsRange(startPage: Int, endPage: Int): Flow<ThumbnailPage> = flow {
        for (pageIndex in startPage..endPage) {
            if (!isActive) break

            getThumbnail(pageIndex)?.let { bitmap ->
                emit(ThumbnailPage(pageIndex, bitmap))
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Pre-generate thumbnails for the entire document in background.
     * Should be called when document is opened.
     */
    suspend fun pregenerateAllThumbnails() = withContext(Dispatchers.Default) {
        val pageCount = pdfRenderer.pageCount
        for (pageIndex in 0 until pageCount) {
            if (!isActive) break
            getThumbnail(pageIndex)
        }
    }

    /**
     * Clear memory cache and release bitmaps to pool.
     */
    suspend fun clearMemoryCache() {
        memoryCache.values.forEach { bitmapPool.release(it) }
        memoryCache.clear()
    }

    /**
     * Clear all caches (memory + disk).
     */
    suspend fun clearAllCaches() {
        clearMemoryCache()
        clearDiskCache()
    }

    private suspend fun generateThumbnail(pageIndex: Int): Bitmap? {
        generationMutex.withLock {
            if (generatingPages.contains(pageIndex)) return null
            generatingPages.add(pageIndex)
        }

        try {
            generationSemaphore.acquire()

            val bitmap = bitmapPool.acquire(thumbWidth, thumbHeight)
            val page = pdfRenderer.openPage(pageIndex)

            try {
                val pageWidth = page.width.toFloat()
                val pageHeight = page.height.toFloat()

                // Calculate scale to fit within thumb dimensions while maintaining aspect ratio
                val scaleX = thumbWidth / pageWidth
                val scaleY = thumbHeight / pageHeight
                val scale = minOf(scaleX, scaleY) * RENDER_SCALE

                val matrix = android.graphics.Matrix().apply {
                    postScale(scale, scale)
                    // Center the page in the thumbnail
                    val dx = (thumbWidth - pageWidth * scale) / 2f
                    val dy = (thumbHeight - pageHeight * scale) / 2f
                    postTranslate(dx, dy)
                }

                val renderRect = android.graphics.Rect(0, 0, thumbWidth, thumbHeight)
                page.render(bitmap, renderRect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Save to disk cache
                saveToDiskCache(pageIndex, bitmap)

                // Add to memory cache with eviction
                addToMemoryCache(pageIndex, bitmap)

                return bitmap
            } finally {
                page.close()
                generationSemaphore.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail for page $pageIndex", e)
            return null
        } finally {
            generationMutex.withLock {
                generatingPages.remove(pageIndex)
            }
        }
    }

    private fun addToMemoryCache(pageIndex: Int, bitmap: Bitmap) {
        if (memoryCache.size >= IN_MEMORY_CACHE_SIZE) {
            // Evict oldest (first) entry
            memoryCache.keys.firstOrNull()?.let { oldest ->
                memoryCache.remove(oldest)?.let { oldBitmap ->
                    bitmapPool.release(oldBitmap)
                }
            }
        }
        memoryCache[pageIndex] = bitmap
    }

    private fun getDiskCacheFile(pageIndex: Int): java.io.File {
        val cacheDir = java.io.File(cacheDir, "thumbnails").apply { mkdirs() }
        return java.io.File(cacheDir, "thumb_${pageIndex}.png")
    }

    private fun loadFromDiskCache(pageIndex: Int): Bitmap? {
        val file = getDiskCacheFile(pageIndex)
        if (!file.exists()) return null

        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail from disk cache: $pageIndex", e)
            null
        }
    }

    private fun saveToDiskCache(pageIndex: Int, bitmap: Bitmap) {
        try {
            val file = getDiskCacheFile(pageIndex)
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail to disk cache: $pageIndex", e)
        }
    }

    private fun clearDiskCache() {
        try {
            java.io.File(cacheDir, "thumbnails").listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear disk cache", e)
        }
    }

    sealed class ThumbnailState {
        object Idle : ThumbnailState()
        data class Generating(val pageIndex: Int, val totalPages: Int) : ThumbnailState()
        data class Complete(val generatedCount: Int) : ThumbnailState()
        data class Error(val message: String) : ThumbnailState()
    }
}
