package com.propdfeditor.batch.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageGeometryTest {

    private val w = 600f
    private val h = 800f

    /** Clockwise page rotation: user point (relative to box origin) -> display point. */
    private fun userToDisplay(rot: Int, x: Float, y: Float): Pair<Float, Float> = when (rot) {
        90 -> y to (w - x)
        180 -> (w - x) to (h - y)
        270 -> (h - y) to x
        else -> x to y
    }

    @Test
    fun displayToUser_isInverseOfClockwiseRotation() {
        val points = listOf(0f to 0f, w to 0f, 0f to h, w to h, 123f to 456f)
        for (rot in listOf(0, 90, 180, 270)) {
            val m = PdfPageGeometry.displayToUser(rot, 0f, 0f, w, h)
            for ((x, y) in points) {
                val (dx, dy) = userToDisplay(rot, x, y)
                assertEquals("x rot=$rot", x, m[0] * dx + m[2] * dy + m[4], 1e-3f)
                assertEquals("y rot=$rot", y, m[1] * dx + m[3] * dy + m[5], 1e-3f)
            }
        }
    }

    @Test
    fun displayToUser_appliesBoxOrigin() {
        val m = PdfPageGeometry.displayToUser(0, 10f, 20f, w, h)
        assertEquals(10f, m[4], 0f)
        assertEquals(20f, m[5], 0f)
    }

    @Test
    fun normalizeRotation_handlesNegativeAndLargeValues() {
        assertEquals(270, PdfPageGeometry.normalizeRotation(-90))
        assertEquals(0, PdfPageGeometry.normalizeRotation(360))
        assertEquals(90, PdfPageGeometry.normalizeRotation(450))
    }

    @Test
    fun displaySize_swapsForQuarterTurns() {
        assertEquals(h to w, PdfPageGeometry.displaySize(90, w, h))
        assertEquals(h to w, PdfPageGeometry.displaySize(270, w, h))
        assertEquals(w to h, PdfPageGeometry.displaySize(180, w, h))
    }
}
