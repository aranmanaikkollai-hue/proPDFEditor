package com.propdf.pdfedit

import com.propdf.pdfedit.convert.ExifOrientation
import com.propdf.pdfedit.geometry.PointPds
import com.propdf.pdfedit.geometry.RectPds
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {
    private val rect = RectPds(0.0, 0.0, 100.0, 200.0)

    private enum class Corner(val x: Double, val y: Double) {
        TOP_LEFT(0.0, 200.0), TOP_RIGHT(100.0, 200.0), BOTTOM_LEFT(0.0, 0.0), BOTTOM_RIGHT(100.0, 0.0)
    }

    // Stored image corners in PDF image space (u right, v up): top-left (0,1), top-right (1,1), bottom-left (0,0), bottom-right (1,0).
    private val stored = listOf(PointPds(0.0, 1.0), PointPds(1.0, 1.0), PointPds(0.0, 0.0), PointPds(1.0, 0.0))

    // Where the stored top-left, top-right, bottom-left, bottom-right corners appear when the picture is shown upright (EXIF definition).
    private val expected = mapOf(
        1 to listOf(Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT),
        2 to listOf(Corner.TOP_RIGHT, Corner.TOP_LEFT, Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT),
        3 to listOf(Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT, Corner.TOP_RIGHT, Corner.TOP_LEFT),
        4 to listOf(Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT, Corner.TOP_LEFT, Corner.TOP_RIGHT),
        5 to listOf(Corner.TOP_LEFT, Corner.BOTTOM_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT),
        6 to listOf(Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT, Corner.TOP_LEFT, Corner.BOTTOM_LEFT),
        7 to listOf(Corner.BOTTOM_RIGHT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.TOP_LEFT),
        8 to listOf(Corner.BOTTOM_LEFT, Corner.TOP_LEFT, Corner.BOTTOM_RIGHT, Corner.TOP_RIGHT)
    )

    @Test
    fun everyOrientationPutsTheStoredCornersWhereExifSaysTheyGo() {
        for ((orientation, corners) in expected) {
            val m = ExifOrientation.matrixFor(orientation, rect)
            for (i in stored.indices) {
                val p = m.transform(stored[i])
                assertEquals("orientation $orientation corner $i x", corners[i].x, p.x, 1e-9)
                assertEquals("orientation $orientation corner $i y", corners[i].y, p.y, 1e-9)
            }
        }
    }

    @Test
    fun theMatrixFillsTheRectangleExactly() {
        val r = RectPds(30.0, 40.0, 130.0, 240.0)
        for (orientation in 1..8) {
            val m = ExifOrientation.matrixFor(orientation, r)
            val xs = stored.map { m.transform(it).x }
            val ys = stored.map { m.transform(it).y }
            assertEquals(r.left, xs.min(), 1e-9); assertEquals(r.right, xs.max(), 1e-9)
            assertEquals(r.bottom, ys.min(), 1e-9); assertEquals(r.top, ys.max(), 1e-9)
        }
    }

    @Test
    fun widthAndHeightSwapOnlyForTheQuarterTurnOrientations() {
        for (o in listOf(1, 2, 3, 4)) assertEquals(Pair(400, 200), ExifOrientation.displaySize(o, 400, 200))
        for (o in listOf(5, 6, 7, 8)) assertEquals(Pair(200, 400), ExifOrientation.displaySize(o, 400, 200))
    }

    @Test
    fun valuesOutsideOneToEightAreTreatedAsNormal() {
        for (o in listOf(0, 9, -1, 255)) {
            assertEquals(1, ExifOrientation.normalize(o))
            assertEquals(Pair(400, 200), ExifOrientation.displaySize(o, 400, 200))
        }
        assertEquals(ExifOrientation.matrixFor(1, rect), ExifOrientation.matrixFor(42, rect))
    }
}
