package com.propdfeditor.core.pdf.signature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the pure placement math. Expected numbers were cross-checked by rendering the same
 * placements with pdfium (tools/signature_fixtures/check_visual_geometry.py); NOT run by Gradle in the authoring
 * environment (no Gradle/SDK there).
 */
class VisualSignatureGeometryTest {

    private val eps = 0.01f

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, eps)

    @Test fun rotation0_fitsAndCentresImage() {
        val p = VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 0, 40f, 180f, 160f, 270f, 40, 20)
        near(120f, p.drawWidth); near(60f, p.drawHeight)
        near(120f, p.a); near(0f, p.b); near(0f, p.c); near(60f, p.d)
        near(40f, p.e); near(45f, p.f)
    }

    @Test fun rotation90_imageStaysUprightOnScreen() {
        val p = VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 90, 30f, 240f, 120f, 360f, 40, 20)
        near(90f, p.drawWidth); near(45f, p.drawHeight)
        near(0f, p.a); near(90f, p.b); near(-45f, p.c); near(0f, p.d)
        near(322.5f, p.e); near(30f, p.f)
    }

    @Test fun rotation180_withOffsetCropBox() {
        val p = VisualSignatureGeometry.place(50f, 40f, 350f, 260f, 180, 30f, 132f, 120f, 198f, 40, 20)
        near(-90f, p.a); near(0f, p.b); near(0f, p.c); near(-45f, p.d)
        near(320f, p.e); near(227.5f, p.f)
    }

    @Test fun negativeAndOver360RotationsNormalise() {
        assertEquals(270, VisualSignatureGeometry.normalizeRotation(-90))
        assertEquals(90, VisualSignatureGeometry.normalizeRotation(450))
        assertEquals(0, VisualSignatureGeometry.normalizeRotation(0))
    }

    @Test fun userRect_rotation0_flipsYAxis() {
        val r = VisualSignatureGeometry.userRect(0f, 0f, 400f, 300f, 0, 220f, 30f, 360f, 105f)
        near(220f, r.x); near(195f, r.y); near(140f, r.width); near(75f, r.height)
    }

    @Test fun userRect_rotation90_swapsAxes() {
        val r = VisualSignatureGeometry.userRect(0f, 0f, 400f, 300f, 90, 165f, 40f, 270f, 140f)
        near(40f, r.x); near(165f, r.y); near(100f, r.width); near(105f, r.height)
    }

    @Test fun rectangleIsClippedToThePage() {
        val r = VisualSignatureGeometry.userRect(0f, 0f, 400f, 300f, 0, 350f, 250f, 900f, 900f)
        near(350f, r.x); near(0f, r.y); near(50f, r.width); near(50f, r.height)
    }

    @Test fun invalidInputIsRejected() {
        val bad = listOf(
            { VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 0, 500f, 10f, 600f, 50f, 10, 10) },  // fully off page
            { VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 0, 10f, 10f, 10.5f, 10.5f, 10, 10) }, // too small
            { VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 0, Float.NaN, 10f, 50f, 50f, 10, 10) },
            { VisualSignatureGeometry.place(0f, 0f, 400f, 300f, 0, 10f, 10f, 50f, 50f, 0, 10) },      // empty image
            { VisualSignatureGeometry.place(0f, 0f, 0f, 300f, 0, 10f, 10f, 50f, 50f, 10, 10) },       // empty page
            { VisualSignatureGeometry.userRect(0f, 0f, 400f, 300f, 0, Float.POSITIVE_INFINITY, 0f, 5f, 5f) }
        )
        for (call in bad) {
            try {
                call()
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(!e.message.isNullOrBlank())
            }
        }
    }
}
