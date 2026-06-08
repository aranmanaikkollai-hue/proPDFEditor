package com.propdf.viewer.model

import android.graphics.Rect
import android.graphics.RectF

/**
 * Represents a single tile in the tile-based rendering grid.
 */
data class Tile(
    val pageIndex: Int,
    val tileX: Int,
    val tileY: Int,
    val zoomLevel: Float,
    val tileSize: Int,
    val scaleFactor: Float
) {
    val id: String = "${pageIndex}_${tileX}_${tileY}_${zoomLevel}_${scaleFactor}"

    val srcRect: RectF = calculateSrcRect()
    val dstRect: Rect = calculateDstRect()

    var priority: Int = 0
        internal set

    var isVisible: Boolean = false
        internal set

    @Volatile
    var bitmapRef: android.graphics.Bitmap? = null

    @Volatile
    var isRendering: Boolean = false

    var lastRequestedAt: Long = System.currentTimeMillis()

    private fun calculateSrcRect(): RectF {
        val pageTileSize = tileSize / zoomLevel
        val left = tileX * pageTileSize
        val top = tileY * pageTileSize
        val right = left + pageTileSize
        val bottom = top + pageTileSize
        return RectF(left, top, right, bottom)
    }

    private fun calculateDstRect(): Rect {
        val left = (tileX * tileSize * scaleFactor).toInt()
        val top = (tileY * tileSize * scaleFactor).toInt()
        val right = left + (tileSize * scaleFactor).toInt()
        val bottom = top + (tileSize * scaleFactor).toInt()
        return Rect(left, top, right, bottom)
    }

    fun intersectsViewport(viewport: Rect): Boolean {
        return Rect.intersects(dstRect, viewport)
    }

    fun distanceFromViewportCenter(viewport: Rect): Float {
        val viewportCx = viewport.centerX().toFloat()
        val viewportCy = viewport.centerY().toFloat()
        val tileCx = dstRect.centerX().toFloat()
        val tileCy = dstRect.centerY().toFloat()
        return kotlin.math.hypot(viewportCx - tileCx, viewportCy - tileCy)
    }

    companion object {
        const val BASE_TILE_SIZE = 512
        const val MAX_TILE_SIZE = 1024
        const val MIN_TILE_SIZE = 256

        fun calculateOptimalTileSize(zoom: Float, screenDensity: Float): Int {
            val base = (BASE_TILE_SIZE * screenDensity).toInt()
            return when {
                zoom > 4.0f -> (base / 2).coerceAtLeast(MIN_TILE_SIZE)
                zoom > 2.0f -> base
                else -> (base * 1.5f).toInt().coerceAtMost(MAX_TILE_SIZE)
            }
        }
    }
}
