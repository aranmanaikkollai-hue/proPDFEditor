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

    // Phase 1: explicit initialisation state. Previously currentZoom started at 1.0f and
    // updateZoom(1.0f) returned early, so a grid at the default zoom was never generated
    // (getAllTiles()/getVisibleTiles() stayed empty until the zoom changed).
    private var generated = false

    fun updateZoom(zoom: Float) {
        if (generated && kotlin.math.abs(currentZoom - zoom) < 0.01f) return

        currentZoom = zoom
        currentTileSize = Tile.calculateOptimalTileSize(zoom, screenDensity)
        regenerateGrid()
    }

    /** Generates the grid at the current zoom if nothing has been generated yet. */
    private fun ensureGenerated() {
        if (!generated) {
            currentTileSize = Tile.calculateOptimalTileSize(currentZoom, screenDensity)
            regenerateGrid()
        }
    }

    fun getVisibleTiles(viewport: Rect): List<Tile> {
        ensureGenerated()
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
        ensureGenerated()
        val expanded = Rect(viewport).apply {
            inset(-marginTiles * currentTileSize, -marginTiles * currentTileSize)
        }
        return allTiles.filter { tile ->
            tile.intersectsViewport(expanded) && !visibleTiles.contains(tile)
        }.sortedBy { it.distanceFromViewportCenter(viewport) }
    }

    fun getAllTiles(): List<Tile> { ensureGenerated(); return allTiles }
    fun containsTile(tileId: String): Boolean { ensureGenerated(); return allTiles.any { it.id == tileId } }

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
        generated = true
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
    fun getGridCols(): Int { ensureGenerated(); return gridCols }
    fun getGridRows(): Int { ensureGenerated(); return gridRows }
}
