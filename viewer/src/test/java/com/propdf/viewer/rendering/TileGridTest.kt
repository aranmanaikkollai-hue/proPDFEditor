package com.propdf.viewer.rendering

import android.graphics.Rect
import com.propdf.viewer.model.Tile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TileGrid calculations.
 */
class TileGridTest {

    private lateinit var tileGrid: TileGrid

    @Before
    fun setup() {
        // A4 page at 72 DPI: 595 x 842 points
        tileGrid = TileGrid(
            pageIndex = 0,
            pageWidth = 595f,
            pageHeight = 842f,
            screenDensity = 2.0f
        )
    }

    @Test
    fun testGridGeneration() {
        tileGrid.updateZoom(1.0f)

        val cols = tileGrid.getGridCols()
        val rows = tileGrid.getGridRows()

        assertTrue(cols > 0)
        assertTrue(rows > 0)

        val allTiles = tileGrid.getAllTiles()
        assertEquals(cols * rows, allTiles.size)
    }

    @Test
    fun testVisibleTiles() {
        tileGrid.updateZoom(1.0f)

        // Viewport covering top-left portion
        val viewport = Rect(0, 0, 512, 512)
        val visible = tileGrid.getVisibleTiles(viewport)

        assertTrue(visible.isNotEmpty())
        visible.forEach { tile ->
            assertTrue(tile.isVisible)
            assertTrue(tile.intersectsViewport(viewport))
        }
    }

    @Test
    fun testAdjacentTiles() {
        tileGrid.updateZoom(1.0f)

        val viewport = Rect(0, 0, 512, 512)
        val visible = tileGrid.getVisibleTiles(viewport)
        val adjacent = tileGrid.getAdjacentTiles(viewport, 1)

        // Adjacent tiles should not overlap with visible
        val visibleIds = visible.map { it.id }.toSet()
        adjacent.forEach { tile ->
            assertFalse(visibleIds.contains(tile.id))
        }
    }

    @Test
    fun testZoomUpdate() {
        tileGrid.updateZoom(1.0f)
        val tilesAt1x = tileGrid.getAllTiles().size

        tileGrid.updateZoom(2.0f)
        val tilesAt2x = tileGrid.getAllTiles().size

        // Higher zoom should produce more tiles
        assertTrue(tilesAt2x >= tilesAt1x)
    }

    @Test
    fun testPriorityOrdering() {
        tileGrid.updateZoom(1.0f)

        val viewport = Rect(256, 256, 768, 768)
        val visible = tileGrid.getVisibleTiles(viewport)

        // First tile should have highest priority (closest to center)
        if (visible.size > 1) {
            assertTrue(visible[0].priority >= visible[1].priority)
        }
    }
}
