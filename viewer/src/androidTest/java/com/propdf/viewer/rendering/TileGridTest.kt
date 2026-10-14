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

        assertTrue(tilesAt2x >= tilesAt1x)
    }

    @Test
    fun testPriorityOrdering() {
        tileGrid.updateZoom(1.0f)

        val viewport = Rect(256, 256, 768, 768)
        val visible = tileGrid.getVisibleTiles(viewport)

        if (visible.size > 1) {
            assertTrue(visible[0].priority >= visible[1].priority)
        }
    }

    // ---- Phase 1 regression tests (TileGrid initial-state defect) ----

    @Test
    fun newGrid_hasTilesAtDefaultZoom_withoutAnyUpdateZoomCall() {
        assertTrue("grid must exist at default zoom", tileGrid.getAllTiles().isNotEmpty())
        assertEquals(tileGrid.getGridCols() * tileGrid.getGridRows(), tileGrid.getAllTiles().size)
    }

    @Test
    fun updateZoomToDefault_onNewGrid_generatesTiles() {
        tileGrid.updateZoom(1.0f)
        assertTrue(tileGrid.getAllTiles().isNotEmpty())
    }

    @Test
    fun repeatedSameZoom_afterInit_doesNotRegenerate() {
        tileGrid.updateZoom(1.0f)
        val first = tileGrid.getAllTiles()
        tileGrid.updateZoom(1.0f)
        assertSame("same zoom must keep the same tile list instance", first, tileGrid.getAllTiles())
    }

    @Test
    fun zoomChange_regeneratesTilesWithNewZoomLevel() {
        tileGrid.updateZoom(1.0f)
        tileGrid.updateZoom(3.0f)
        assertTrue(tileGrid.getAllTiles().isNotEmpty())
        assertTrue(tileGrid.getAllTiles().all { it.zoomLevel == 3.0f })
    }

    @Test
    fun tilesBelongToTheGridsPage_andCoordinatesAreInRange() {
        val other = TileGrid(pageIndex = 7, pageWidth = 400f, pageHeight = 300f, screenDensity = 2.0f)
        val tiles = other.getAllTiles()
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.pageIndex == 7 })
        assertTrue(tiles.all { it.tileX in 0 until other.getGridCols() && it.tileY in 0 until other.getGridRows() })
    }

    @Test
    fun newGridInstance_afterInvalidation_isIndependent() {
        tileGrid.updateZoom(3.0f)
        val fresh = TileGrid(0, 595f, 842f, 2.0f)
        assertTrue(fresh.getAllTiles().all { it.zoomLevel == 1.0f })
    }
}
