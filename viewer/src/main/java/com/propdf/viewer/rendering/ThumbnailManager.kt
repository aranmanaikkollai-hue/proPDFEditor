package com.propdf.viewer.rendering

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.util.Log
import com.propdf.viewer.model.ThumbnailPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Memory-efficient thumbnail generation and caching for sidebar navigation.
 */
class ThumbnailManager(
    private val bitmapPool: BitmapPool,
    private val pdfRenderer: PdfRenderer,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "ThumbnailManager"
        private const val THUMB_WIDTH_DP = 120
        private const val THUMB_HEIGHT_DP = 160
        private const val IN_MEMORY_CACHE_SIZE = 20
        private const val MAX_CONCURRENT_GENERATION = 3
        private const val RENDER_SCALE = 0.3f
    }

    private val memoryCache = ConcurrentHashMap<Int, Bitmap>(IN_MEMORY_CACHE_SIZE)
    private val generationMutex = Mutex()
    private val generatingPages = mutableSetOf<Int>()
    private val generationSemaphore = Semaphore(MAX_CONCURRENT_GENERATION)

    private var thumbWidth = THUMB_WIDTH_DP * 2
    private var thumbHeight = THUMB_HEIGHT_DP * 2

    fun initialize(density: Float) {
        thumbWidth = (THUMB_WIDTH_DP * density).toInt()
        thumbHeight = (THUMB_HEIGHT_DP * density).toInt()
    }

    suspend fun getThumbnail(pageIndex: Int): Bitmap? = withContext(Dispatchers.Default) {
        if (!isActive) return@withContext null

        memoryCache[pageIndex]?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        loadFromDiskCache(pageIndex)?.let { diskCached ->
            memoryCache[pageIndex] = diskCached
            return@withContext diskCached
        }

        generateThumbnail(pageIndex)
    }

    fun generateThumbnailsRange(startPage: Int, endPage: Int): Flow<ThumbnailPage> = flow {
        for (pageIndex in startPage..endPage) {
            if (!currentCoroutineContext().isActive) break
            getThumbnail(pageIndex)?.let { bitmap ->
                emit(ThumbnailPage(pageIndex, bitmap))
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun pregenerateAllThumbnails() = withContext(Dispatchers.Default) {
        val pageCount = pdfRenderer.pageCount
        for (pageIndex in 0 until pageCount) {
            if (!isActive) break
            getThumbnail(pageIndex)
        }
    }

    suspend fun clearMemoryCache() {
        memoryCache.values.forEach { bitmapPool.release(it) }
        memoryCache.clear()
    }

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

                val scaleX = thumbWidth / pageWidth
                val scaleY = thumbHeight / pageHeight
                val scale = minOf(scaleX, scaleY) * RENDER_SCALE

                val matrix = android.graphics.Matrix().apply {
                    postScale(scale, scale)
                    val dx = (thumbWidth - pageWidth * scale) / 2f
                    val dy = (thumbHeight - pageHeight * scale) / 2f
                    postTranslate(dx, dy)
                }

                val renderRect = android.graphics.Rect(0, 0, thumbWidth, thumbHeight)
                page.render(bitmap, renderRect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                saveToDiskCache(pageIndex, bitmap)
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
            memoryCache.keys.firstOrNull()?.let { oldest ->
                memoryCache.remove(oldest)?.let { oldBitmap ->
                    bitmapPool.releaseSync(oldBitmap)
                }
            }
        }
        memoryCache[pageIndex] = bitmap
    }

    private fun getDiskCacheFile(pageIndex: Int): File {
        val cacheDir = File(cacheDir, "thumbnails").apply { mkdirs() }
        return File(cacheDir, "thumb_${pageIndex}.png")
    }

    private fun loadFromDiskCache(pageIndex: Int): Bitmap? {
        val file = getDiskCacheFile(pageIndex)
        if (!file.exists()) return null

        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail from disk cache: $pageIndex", e)
            null
        }
    }

    private fun saveToDiskCache(pageIndex: Int, bitmap: Bitmap) {
        try {
            val file = getDiskCacheFile(pageIndex)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail to disk cache: $pageIndex", e)
        }
    }

    private fun clearDiskCache() {
        try {
            File(cacheDir, "thumbnails").listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear disk cache", e)
        }
    }
}
