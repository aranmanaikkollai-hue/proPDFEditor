package com.propdf.viewer.rendering

import android.graphics.Rect
import com.propdf.viewer.model.Tile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the tile grid for a single PDF page.
 * Calculates which tiles are needed for a given viewport and zoom level.
 */
class TileGrid(
    val pageIndex: Int,
    private val pageWidth: Float,   // PDF page width in points
    private val pageHeight: Float,  // PDF page height in points
    private val screenDensity: Float
) {
    private var currentZoom = 1.0f
    private var currentTileSize = Tile.BASE_TILE_SIZE

    /** All tiles for current zoom level (lazily computed) */
    private var allTiles: List<Tile> = emptyList()

    /** Currently visible tiles */
    private var visibleTiles: Set<Tile> = emptySet()

    /** Grid dimensions */
    private var gridCols = 0
    private var gridRows = 0

    /**
     * Update zoom level and recalculate grid if needed.
     */
    fun updateZoom(zoom: Float) {
        if (kotlin.math.abs(currentZoom - zoom) < 0.01f) return

        currentZoom = zoom
        currentTileSize = Tile.calculateOptimalTileSize(zoom, screenDensity)
        regenerateGrid()
    }

    /**
     * Get tiles that intersect the given viewport rect.
     * Returns tiles sorted by priority (center-first).
     */
    fun getVisibleTiles(viewport: Rect): List<Tile> {
        val visible = allTiles.filter { it.intersectsViewport(viewport) }
        visible.forEach { tile ->
            tile.isVisible = true
            tile.priority = calculatePriority(tile, viewport)
        }
        visibleTiles = visible.toSet()

        // Sort by priority (descending) then distance from center
        return visible.sortedWith(compareByDescending<Tile> { it.priority }
            .thenBy { it.distanceFromViewportCenter(viewport) })
    }

    /**
     * Get tiles that are near the viewport but not currently visible.
     * Used for preloading adjacent content.
     */
    fun getAdjacentTiles(viewport: Rect, marginTiles: Int = 1): List<Tile> {
        val expanded = Rect(viewport).apply {
            inset(-marginTiles * currentTileSize, -marginTiles * currentTileSize)
        }
        return allTiles.filter { tile ->
            tile.intersectsViewport(expanded) && !visibleTiles.contains(tile)
        }.sortedBy { it.distanceFromViewportCenter(viewport) }
    }

    /**
     * Get all tiles currently managed by this grid.
     */
    fun getAllTiles(): List<Tile> = allTiles

    /**
     * Check if a tile ID belongs to this page's grid.
     */
    fun containsTile(tileId: String): Boolean {
        return allTiles.any { it.id == tileId }
    }

    private fun regenerateGrid() {
        val scaledWidth = pageWidth * currentZoom * screenDensity
        val scaledHeight = pageHeight * currentZoom * screenDensity

        gridCols = max(1, ceil(scaledWidth / currentTileSize).toInt())
        gridRows = max(1, ceil(scaledHeight / currentTileSize).toInt())

        allTiles = buildList {
            for (y in 0 until gridRows) {
                for (x in 0 until gridCols) {
                    add(Tile(
                        pageIndex = pageIndex,
                        tileX = x,
                        tileY = y,
                        zoomLevel = currentZoom,
                        tileSize = currentTileSize,
                        scaleFactor = screenDensity
                    ))
                }
            }
        }

        visibleTiles = emptySet()
    }

    private fun calculatePriority(tile: Tile, viewport: Rect): Int {
        val distance = tile.distanceFromViewportCenter(viewport)
        val maxDist = kotlin.math.hypot(
            viewport.width().toFloat(),
            viewport.height().toFloat()
        )
        val normalizedDist = 1f - (distance / maxDist).coerceIn(0f, 1f)
        return (normalizedDist * 1000).toInt()
    }

    fun getPageWidth(): Float = pageWidth * currentZoom * screenDensity
    fun getPageHeight(): Float = pageHeight * currentZoom * screenDensity
    fun getGridCols(): Int = gridCols
    fun getGridRows(): Int = gridRows
}
