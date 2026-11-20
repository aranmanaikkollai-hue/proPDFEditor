package com.propdf.pdfedit

import com.propdf.pdfedit.convert.RenderSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RenderSizingTest {
    private val big = 1_000_000_000L

    @Test
    fun a4AtSeventyTwoDpiIsItsPointSize() {
        val s = RenderSizing.compute(595.0, 842.0, 72, big)
        assertEquals(595, s.widthPx); assertEquals(842, s.heightPx)
        assertFalse(s.reduced)
        assertEquals(72.0, s.effectiveDpi, 1e-6)
    }

    @Test
    fun a4AtOneFiftyDpi() {
        val s = RenderSizing.compute(595.0, 842.0, 150, big)
        assertEquals(1240, s.widthPx); assertEquals(1754, s.heightPx)
        assertFalse(s.reduced)
    }

    @Test
    fun pixelLimitReducesTheSizeAndKeepsTheAspectRatio() {
        val s = RenderSizing.compute(595.0, 842.0, 300, 1_000_000L)
        assertTrue(s.reduced)
        assertTrue(s.widthPx.toLong() * s.heightPx <= 1_000_000L)
        assertTrue(s.effectiveDpi < 300.0)
        assertTrue(abs(s.widthPx.toDouble() / s.heightPx - 595.0 / 842.0) < 0.01)
        assertTrue("should use most of the allowance", s.widthPx.toLong() * s.heightPx > 900_000L)
    }

    @Test
    fun dpiIsClampedToTheSupportedRange() {
        assertEquals(600.0, RenderSizing.compute(595.0, 842.0, 5000, big).effectiveDpi, 1e-6)
        assertEquals(36.0, RenderSizing.compute(595.0, 842.0, 1, big).effectiveDpi, 1e-6)
    }

    @Test
    fun anExtremelyTallPageIsKeptWithinTheSideLimit() {
        val s = RenderSizing.compute(100.0, 20000.0, 600, big)
        assertTrue(s.reduced)
        assertTrue(maxOf(s.widthPx, s.heightPx) <= RenderSizing.MAX_SIDE_PX)
        assertTrue(s.widthPx >= 1)
    }

    @Test
    fun degenerateInputNeverGivesAZeroOrNegativeSize() {
        for ((w, h) in listOf(0.0 to 0.0, -5.0 to 10.0, 0.001 to 0.001)) {
            val s = RenderSizing.compute(w, h, 150, big)
            assertTrue(s.widthPx >= 1 && s.heightPx >= 1)
        }
        val tiny = RenderSizing.compute(595.0, 842.0, 150, 0L)
        assertTrue(tiny.widthPx >= 1 && tiny.heightPx >= 1)
    }

    @Test
    fun heightOrWidthLimitedByPixelsStillCoversTheWholePage() {
        val s = RenderSizing.compute(842.0, 595.0, 300, 2_000_000L)
        assertTrue(s.widthPx > s.heightPx)
        assertTrue(s.widthPx.toLong() * s.heightPx <= 2_000_000L)
    }
}
