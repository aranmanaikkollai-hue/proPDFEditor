package com.propdf.pdfedit

import com.propdf.pdfedit.geometry.Matrix2D
import com.propdf.pdfedit.geometry.PointPds
import com.propdf.pdfedit.geometry.RectPds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Matrix2DTest {
    private val eps = 1e-9

    private fun assertPoint(x: Double, y: Double, p: PointPds) {
        assertEquals(x, p.x, 1e-6)
        assertEquals(y, p.y, 1e-6)
    }

    @Test
    fun thenAppliesTheReceiverFirst() {
        val scale = Matrix2D.scale(2.0, 3.0)
        val move = Matrix2D(1.0, 0.0, 0.0, 1.0, 10.0, 20.0)
        // scale first, then move
        assertPoint(12.0, 26.0, scale.then(move).transform(PointPds(1.0, 2.0)))
        // move first, then scale
        assertPoint(22.0, 66.0, move.then(scale).transform(PointPds(1.0, 2.0)))
    }

    @Test
    fun inverseUndoesTheMatrix() {
        val m = Matrix2D(0.0, 1.0, -1.0, 0.0, 612.0, 5.0)
        val inv = m.inverse()
        assertNotNull(inv)
        val p = PointPds(33.0, 44.0)
        assertPoint(p.x, p.y, inv!!.transform(m.transform(p)))
        val id = m.then(inv)
        assertEquals(1.0, id.a, eps); assertEquals(0.0, id.b, eps)
        assertEquals(0.0, id.c, eps); assertEquals(1.0, id.d, eps)
        assertEquals(0.0, id.e, 1e-9); assertEquals(0.0, id.f, 1e-9)
    }

    @Test
    fun singularMatrixHasNoInverse() {
        assertNull(Matrix2D(0.0, 0.0, 0.0, 0.0, 5.0, 5.0).inverse())
        assertNull(Matrix2D(1.0, 2.0, 2.0, 4.0, 0.0, 0.0).inverse())
    }

    @Test
    fun unitSquareMapsOntoTheRectangle() {
        val r = RectPds(10.0, 20.0, 110.0, 70.0)
        val m = Matrix2D.unitSquareTo(r)
        assertPoint(10.0, 20.0, m.transform(PointPds(0.0, 0.0)))
        assertPoint(110.0, 70.0, m.transform(PointPds(1.0, 1.0)))
    }

    @Test
    fun transformBoundsOfARotationSwapsWidthAndHeight() {
        val rot = Matrix2D(0.0, 1.0, -1.0, 0.0, 0.0, 0.0)
        val b = rot.transformBounds(RectPds(0.0, 0.0, 100.0, 50.0))
        assertEquals(50.0, b.width, 1e-9)
        assertEquals(100.0, b.height, 1e-9)
    }

    @Test
    fun rectOfNormalizesCorners() {
        val r = RectPds.of(50.0, 80.0, 10.0, 20.0)
        assertEquals(10.0, r.left, eps); assertEquals(20.0, r.bottom, eps)
        assertEquals(50.0, r.right, eps); assertEquals(80.0, r.top, eps)
    }
}
