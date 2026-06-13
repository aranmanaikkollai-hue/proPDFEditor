package com.propdf.viewer.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.util.Log
import com.propdf.viewer.model.Tile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-grade tile renderer using Android's PdfRenderer.
 */
class TileRenderer(
    private val bitmapPool: BitmapPool,
    private val pdfRenderer: PdfRenderer,
    private val pageCount: Int
) {
    companion object {
        private const val TAG = "TileRenderer"
        private const val MAX_CONCURRENT_RENDERS = 4
        private const val CACHE_SIZE_TILES = 60
    }

    private val tileCache = ConcurrentHashMap<String, Bitmap>(CACHE_SIZE_TILES)
    private val renderingMutex = Mutex()
    private val renderingTiles = mutableSetOf<String>()
    private val renderSemaphore = Semaphore(MAX_CONCURRENT_RENDERS)

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    suspend fun renderTile(tile: Tile): Bitmap? = withContext(Dispatchers.Default) {
        if (!isActive) return@withContext null

        tileCache[tile.id]?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderingMutex.withLock {
            if (renderingTiles.contains(tile.id)) {
                return@withContext null
            }
            renderingTiles.add(tile.id)
        }

        try {
            renderSemaphore.acquire()
            if (!isActive) return@withContext null

            val bitmap = bitmapPool.acquire(tile.tileSize, tile.tileSize)
            val canvas = Canvas(bitmap)

            val page = pdfRenderer.openPage(tile.pageIndex)
            try {
                if (!isActive) {
                    bitmapPool.release(bitmap)
                    return@withContext null
                }

                val pageWidth = page.width.toFloat()
                val pageHeight = page.height.toFloat()

                val tileScaleX = pageWidth / tile.srcRect.width()
                val tileScaleY = pageHeight / tile.srcRect.height()

                val matrix = Matrix().apply {
                    val scale = tile.tileSize.toFloat() / tile.srcRect.width()
                    postScale(scale * tile.zoomLevel * tile.scaleFactor, scale * tile.zoomLevel * tile.scaleFactor)
                    postTranslate(-tile.srcRect.left * scale * tile.zoomLevel * tile.scaleFactor,
                                  -tile.srcRect.top * scale * tile.zoomLevel * tile.scaleFactor)
                }

                val renderRect = Rect(0, 0, tile.tileSize, tile.tileSize)
                page.render(bitmap, renderRect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                if (tileCache.size >= CACHE_SIZE_TILES) {
                    evictOldestFromCache()
                }
                tileCache[tile.id] = bitmap

                return@withContext bitmap
            } finally {
                page.close()
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Tile render cancelled: ${tile.id}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render tile ${tile.id}", e)
            return@withContext null
        } finally {
            renderSemaphore.release()
            renderingMutex.withLock {
                renderingTiles.remove(tile.id)
            }
        }
    }

    suspend fun cancelPage(pageIndex: Int) {
        renderingMutex.withLock {
            renderingTiles.removeAll { it.startsWith("${pageIndex}_") }
        }
    }

    suspend fun clearCache() {
        tileCache.values.forEach { bitmapPool.release(it) }
        tileCache.clear()
    }

    suspend fun trimCache(keepTileIds: Set<String>) {
        val toRemove = tileCache.keys.filter { it !in keepTileIds }
        toRemove.forEach { id ->
            tileCache.remove(id)?.let { bitmapPool.release(it) }
        }
    }

    private fun evictOldestFromCache() {
        tileCache.keys.firstOrNull()?.let { key ->
            tileCache.remove(key)?.let { bitmapPool.releaseSync(it) }
        }
    }

    fun getCacheStats(): CacheStats = CacheStats(
        cachedTiles = tileCache.size,
        maxCacheTiles = CACHE_SIZE_TILES,
        renderingTiles = renderingTiles.size
    )

    data class CacheStats(
        val cachedTiles: Int,
        val maxCacheTiles: Int,
        val renderingTiles: Int
    )
}
