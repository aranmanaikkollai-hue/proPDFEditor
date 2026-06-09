package com.propdf.viewer.rendering

import android.graphics.Rect
import com.propdf.viewer.model.Tile
import kotlin.math.ceil
import kotlin.math.max

/**
 * Manages the tile grid for a single PDF page.
 */
class TileGrid(
    val pageIndex: Int,
    private val pageWidth: Float,
    private val pageHeight: Float,
    private val screenDensity: Float
) {
    private var currentZoom = 1.0f
    private var currentTileSize = Tile.BASE_TILE_SIZE

    private var allTiles: List<Tile> = emptyList()
    private var visibleTiles: Set<Tile> = emptySet()
    private var gridCols = 0
    private var gridRows = 0

    fun updateZoom(zoom: Float) {
        if (kotlin.math.abs(currentZoom - zoom) < 0.01f) return

        currentZoom = zoom
        currentTileSize = Tile.calculateOptimalTileSize(zoom, screenDensity)
        regenerateGrid()
    }

    fun getVisibleTiles(viewport: Rect): List<Tile> {
        val visible = allTiles.filter { it.intersectsViewport(viewport) }
        visible.forEach { tile ->
            tile.isVisible = true
            tile.priority = calculatePriority(tile, viewport)
        }
        visibleTiles = visible.toSet()

        return visible.sortedWith(compareByDescending<Tile> { it.priority }
            .thenBy { it.distanceFromViewportCenter(viewport) })
    }

    fun getAdjacentTiles(viewport: Rect, marginTiles: Int = 1): List<Tile> {
        val expanded = Rect(viewport).apply {
            inset(-marginTiles * currentTileSize, -marginTiles * currentTileSize)
        }
        return allTiles.filter { tile ->
            tile.intersectsViewport(expanded) && !visibleTiles.contains(tile)
        }.sortedBy { it.distanceFromViewportCenter(viewport) }
    }

    fun getAllTiles(): List<Tile> = allTiles
    fun containsTile(tileId: String): Boolean = allTiles.any { it.id == tileId }

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
