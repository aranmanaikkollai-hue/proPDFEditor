package com.propdf.viewer.model

import android.graphics.Rect
import android.graphics.RectF

/**
 * Represents a single tile in the tile-based rendering grid.
 * A tile is a partial viewport of a PDF page rendered at a specific zoom level.
 */
data class Tile(
    val pageIndex: Int,
    val tileX: Int,        // Grid column
    val tileY: Int,        // Grid row
    val zoomLevel: Float,
    val tileSize: Int,     // Pixel dimension (square tiles)
    val scaleFactor: Float // Device pixel density scale
) {
    /** Unique identifier for this tile */
    val id: String = "${pageIndex}_${tileX}_${tileY}_${zoomLevel}_${scaleFactor}"

    /** The source rect in page coordinates (PDF space) */
    val srcRect: RectF = calculateSrcRect()

    /** The destination rect in screen coordinates */
    val dstRect: Rect = calculateDstRect()

    /** Priority for rendering (higher = more urgent) */
    var priority: Int = 0
        internal set

    /** Whether this tile is currently visible in the viewport */
    var isVisible: Boolean = false
        internal set

    /** Rendered bitmap reference (null until rendered) */
    @Volatile
    var bitmapRef: android.graphics.Bitmap? = null

    /** Whether this tile is currently being rendered */
    @Volatile
    var isRendering: Boolean = false

    /** Timestamp when this tile was last requested */
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

    /**
     * Check if this tile intersects with the given viewport rect.
     */
    fun intersectsViewport(viewport: Rect): Boolean {
        return Rect.intersects(dstRect, viewport)
    }

    /**
     * Calculate distance from viewport center for priority sorting.
     */
    fun distanceFromViewportCenter(viewport: Rect): Float {
        val viewportCx = viewport.centerX().toFloat()
        val viewportCy = viewport.centerY().toFloat()
        val tileCx = dstRect.centerX().toFloat()
        val tileCy = dstRect.centerY().toFloat()
        return kotlin.math.hypot(viewportCx - tileCx, viewportCy - tileCy)
    }

    companion object {
        /** Base tile size in pixels at 1.0 zoom */
        const val BASE_TILE_SIZE = 512

        /** Maximum tile size to prevent excessive memory use */
        const val MAX_TILE_SIZE = 1024

        /** Minimum tile size for high zoom levels */
        const val MIN_TILE_SIZE = 256

        /**
         * Calculate optimal tile size based on zoom and device characteristics.
         */
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
