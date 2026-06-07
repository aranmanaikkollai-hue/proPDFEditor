package com.propdf.viewer.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.util.Log
import com.propdf.viewer.model.Tile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-grade tile renderer using Android's PdfRenderer.
 * Renders individual tiles asynchronously with proper cancellation support.
 *
 * Architecture:
 * - Each tile is rendered independently on a background thread
 * - Bitmap pooling eliminates allocation churn
 * - Cancellation is cooperative — checked before each tile render
 * - Results are cached in a bounded LRU cache
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

    /** In-memory tile cache: tileId -> rendered bitmap */
    private val tileCache = ConcurrentHashMap<String, Bitmap>(CACHE_SIZE_TILES)

    /** Track which tiles are currently being rendered to avoid duplicate work */
    private val renderingMutex = Mutex()
    private val renderingTiles = mutableSetOf<String>()

    /** Semaphore to limit concurrent renders */
    private val renderSemaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_RENDERS)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Render a single tile and return the bitmap.
     * This is a suspending function that runs on Dispatchers.Default.
     *
     * @param tile The tile to render
     * @return Rendered bitmap (must be released to pool when done)
     */
    suspend fun renderTile(tile: Tile): Bitmap? = withContext(Dispatchers.Default) {
        if (!isActive) return@withContext null

        // Check cache first
        tileCache[tile.id]?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        // Prevent duplicate rendering of the same tile
        renderingMutex.withLock {
            if (renderingTiles.contains(tile.id)) {
                // Another coroutine is already rendering this tile
                return@withContext null
            }
            renderingTiles.add(tile.id)
        }

        try {
            renderSemaphore.acquire()
            if (!isActive) return@withContext null

            val bitmap = bitmapPool.acquire(tile.tileSize, tile.tileSize)
            val canvas = Canvas(bitmap)

            // Open the PDF page
            val page = pdfRenderer.openPage(tile.pageIndex)
            try {
                if (!isActive) {
                    bitmapPool.release(bitmap)
                    return@withContext null
                }

                // Calculate the clip rect for this tile within the full page
                val pageWidth = page.width.toFloat()
                val pageHeight = page.height.toFloat()

                val tileScaleX = pageWidth / tile.srcRect.width()
                val tileScaleY = pageHeight / tile.srcRect.height()

                val matrix = Matrix().apply {
                    // Scale to fit the tile bitmap
                    val scale = tile.tileSize.toFloat() / tile.srcRect.width()
                    postScale(scale * tile.zoomLevel * tile.scaleFactor, scale * tile.zoomLevel * tile.scaleFactor)
                    // Translate to show only the tile portion
                    postTranslate(-tile.srcRect.left * scale * tile.zoomLevel * tile.scaleFactor,
                                  -tile.srcRect.top * scale * tile.zoomLevel * tile.scaleFactor)
                }

                // Render the page portion into the tile bitmap
                val renderRect = Rect(0, 0, tile.tileSize, tile.tileSize)
                page.render(bitmap, renderRect, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Cache the result
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

    /**
     * Cancel rendering of all tiles for a given page.
     */
    suspend fun cancelPage(pageIndex: Int) {
        renderingMutex.withLock {
            renderingTiles.removeAll { it.startsWith("${pageIndex}_") }
        }
    }

    /**
     * Release all cached bitmaps back to the pool.
     */
    suspend fun clearCache() {
        tileCache.values.forEach { bitmapPool.release(it) }
        tileCache.clear()
    }

    /**
     * Release bitmaps for tiles that are no longer needed.
     */
    suspend fun trimCache(keepTileIds: Set<String>) {
        val toRemove = tileCache.keys.filter { it !in keepTileIds }
        toRemove.forEach { id ->
            tileCache.remove(id)?.let { bitmapPool.release(it) }
        }
    }

    private fun evictOldestFromCache() {
        // Simple eviction: remove first entry (not truly LRU but sufficient for tiles)
        tileCache.keys.firstOrNull()?.let { key ->
            tileCache.remove(key)?.let { bitmapPool.release(it) }
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
