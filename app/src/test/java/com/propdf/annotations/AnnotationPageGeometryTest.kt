package com.propdf.annotations

import com.propdf.annotations.export.AnnotationPageGeometry as G
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 1: pure JVM checks of the display-to-user matrix used when flattening annotations. */
class AnnotationPageGeometryTest {

    // Display space = bottom-left origin, size dispW x dispH. Page box 400 x 600 at origin (llx, lly).
    private fun user(rot: Int, llx: Float, lly: Float, dx: Float, dy: Float) =
        G.mapPoint(G.displayToUser(rot, llx, lly, 400f, 600f), dx, dy)

    @Test fun rotation0_isTranslationByBoxOrigin() {
        assertEquals(10f + 5f to 20f + 7f, user(0, 10f, 20f, 5f, 7f))
    }

    @Test fun rotation90_displayOriginMapsToBoxTopLeft() {
        // Display bottom-left of a 90-degree page is the user-space top-left corner (llx, lly + height).
        val (x, y) = user(90, 0f, 0f, 0f, 0f)
        assertEquals(400f, x, 0.001f); assertEquals(0f, y, 0.001f)
    }

    @Test fun rotation180_displayOriginMapsToBoxTopRight() {
        val (x, y) = user(180, 0f, 0f, 0f, 0f)
        assertEquals(400f, x, 0.001f); assertEquals(600f, y, 0.001f)
    }

    @Test fun rotation270_displayOriginMapsToBoxTopLeft() {
        val (x, y) = user(270, 0f, 0f, 0f, 0f)
        assertEquals(0f, x, 0.001f); assertEquals(600f, y, 0.001f)
    }

    @Test fun displaySizeSwapsFor90And270Only() {
        assertEquals(400f, G.displayWidth(0, 400f, 600f), 0f)
        assertEquals(600f, G.displayWidth(90, 400f, 600f), 0f)
        assertEquals(400f, G.displayWidth(180, 400f, 600f), 0f)
        assertEquals(600f, G.displayWidth(270, 400f, 600f), 0f)
        assertEquals(400f, G.displayHeight(90, 400f, 600f), 0f)
    }

    @Test fun negativeAndLargeRotationsNormalise() {
        assertEquals(270, G.normalizeRotation(-90)); assertEquals(90, G.normalizeRotation(450))
    }

    @Test fun cropBoxOriginIsAddedExactlyOnce() {
        val (x, y) = user(0, 36f, 48f, 100f, 200f)
        assertEquals(136f, x, 0.001f); assertEquals(248f, y, 0.001f)
    }
}
