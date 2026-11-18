package com.propdf.pdfedit

import com.propdf.pdfedit.geometry.Matrix2D
import com.propdf.pdfedit.geometry.PageGeometry
import com.propdf.pdfedit.geometry.PointPds
import com.propdf.pdfedit.geometry.RectPds
import org.junit.Assert.assertEquals
import org.junit.Test

class PageGeometryTest {
    private val eps = 1e-6

    private fun assertMatrix(expected: DoubleArray, m: Matrix2D) {
        assertEquals(expected[0], m.a, eps); assertEquals(expected[1], m.b, eps)
        assertEquals(expected[2], m.c, eps); assertEquals(expected[3], m.d, eps)
        assertEquals(expected[4], m.e, eps); assertEquals(expected[5], m.f, eps)
    }

    /** Same four matrices as app PdfPageGeometry.displayToUser for box llx=10, lly=20, width=300, height=400. */
    @Test
    fun matricesMatchTheExistingPdfPageGeometryConvention() {
        fun g(rot: Int) = PageGeometry(10.0, 20.0, 310.0, 420.0, rot)
        assertMatrix(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 10.0, 20.0), g(0).displayToUser)
        assertMatrix(doubleArrayOf(0.0, 1.0, -1.0, 0.0, 310.0, 20.0), g(90).displayToUser)
        assertMatrix(doubleArrayOf(-1.0, 0.0, 0.0, -1.0, 310.0, 420.0), g(180).displayToUser)
        assertMatrix(doubleArrayOf(0.0, -1.0, 1.0, 0.0, 10.0, 420.0), g(270).displayToUser)
    }

    @Test
    fun displaySizeSwapsForQuarterTurns() {
        assertEquals(612.0, PageGeometry(0.0, 0.0, 612.0, 792.0, 0).displayWidth, eps)
        assertEquals(612.0, PageGeometry(0.0, 0.0, 612.0, 792.0, 180).displayWidth, eps)
        assertEquals(792.0, PageGeometry(0.0, 0.0, 612.0, 792.0, 90).displayWidth, eps)
        assertEquals(612.0, PageGeometry(0.0, 0.0, 612.0, 792.0, 270).displayHeight, eps)
    }

    @Test
    fun rotationIsNormalizedToAMultipleOf90() {
        assertEquals(270, PageGeometry(0.0, 0.0, 10.0, 10.0, -90).rotation)
        assertEquals(90, PageGeometry(0.0, 0.0, 10.0, 10.0, 450).rotation)
        assertEquals(90, PageGeometry(0.0, 0.0, 10.0, 10.0, 100).rotation)
        assertEquals(0, PageGeometry(0.0, 0.0, 10.0, 10.0, 360).rotation)
    }

    @Test
    fun cornersOfARotatedPageLandWhereTheyShould() {
        val g = PageGeometry(0.0, 0.0, 612.0, 792.0, 90)
        // The bottom-left of the displayed page is the bottom-right of the stored page.
        val bl = g.displayToUser.transform(PointPds(0.0, 0.0))
        assertEquals(612.0, bl.x, eps); assertEquals(0.0, bl.y, eps)
        // The top-right of the displayed page is the top-left of the stored page.
        val tr = g.displayToUser.transform(PointPds(792.0, 612.0))
        assertEquals(0.0, tr.x, eps); assertEquals(792.0, tr.y, eps)
    }

    @Test
    fun pointsRoundTripForEveryRotationOffsetBoxAndUserUnit() {
        for (rot in listOf(0, 90, 180, 270)) {
            for (unit in listOf(1.0, 2.0, 0.5)) {
                val g = PageGeometry(36.0, 54.0, 400.0, 700.0, rot, unit)
                val p = PointPds(120.5, 77.25)
                val back = g.userToDisplay.transform(g.displayToUser.transform(p))
                assertEquals("rot $rot unit $unit x", p.x, back.x, eps)
                assertEquals("rot $rot unit $unit y", p.y, back.y, eps)
            }
        }
    }

    @Test
    fun userUnitScalesTheDisplaySize() {
        val g = PageGeometry(0.0, 0.0, 306.0, 396.0, 0, 2.0)
        assertEquals(612.0, g.displayWidth, eps)
        assertEquals(792.0, g.displayHeight, eps)
    }

    @Test
    fun invertedBoxCoordinatesAreNormalized() {
        val a = PageGeometry(10.0, 20.0, 310.0, 420.0, 90)
        val b = PageGeometry(310.0, 420.0, 10.0, 20.0, 90)
        assertMatrix(doubleArrayOf(a.displayToUser.a, a.displayToUser.b, a.displayToUser.c, a.displayToUser.d, a.displayToUser.e, a.displayToUser.f), b.displayToUser)
    }

    @Test
    fun placementMatrixAndPlacementBoundsAreInverseOperations() {
        val wanted = RectPds(50.0, 60.0, 150.0, 260.0)
        for (rot in listOf(0, 90, 180, 270)) {
            val g = PageGeometry(20.0, 30.0, 420.0, 630.0, rot)
            val back = g.imagePlacementToPds(g.placementMatrixFor(wanted))
            assertEquals("rot $rot left", wanted.left, back.left, eps)
            assertEquals("rot $rot bottom", wanted.bottom, back.bottom, eps)
            assertEquals("rot $rot right", wanted.right, back.right, eps)
            assertEquals("rot $rot top", wanted.top, back.top, eps)
        }
    }

    @Test
    fun userRectOnARotatedPageHasHandComputedDisplayBounds() {
        // Page 612 x 792 rotated 90: display X = user y, display Y = 612 - user x.
        val g = PageGeometry(0.0, 0.0, 612.0, 792.0, 90)
        val m = Matrix2D(200.0, 0.0, 0.0, 100.0, 100.0, 500.0) // image at user (100,500) size 200 x 100
        val b = g.imagePlacementToPds(m)
        assertEquals(500.0, b.left, eps); assertEquals(600.0, b.right, eps)
        assertEquals(312.0, b.bottom, eps); assertEquals(512.0, b.top, eps)
    }

    @Test
    fun bitmapPixelsRoundTrip() {
        val g = PageGeometry(0.0, 0.0, 612.0, 792.0, 0)
        val p = PointPds(100.0, 700.0)
        val (px, py) = g.pdsToBitmap(p, 2.0)
        assertEquals(200.0, px, eps)
        assertEquals(184.0, py, eps)
        val back = g.bitmapToPds(px, py, 2.0)
        assertEquals(p.x, back.x, eps); assertEquals(p.y, back.y, eps)
    }
}
