package com.propdf.pdfedit

import com.propdf.pdfedit.convert.ImagePagePlanner
import com.propdf.pdfedit.convert.PageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePagePlannerTest {
    private val eps = 1e-6

    @Test
    fun portraitPictureOnA4FitsInsideTheMarginsAndIsCentred() {
        val p = ImagePagePlanner.plan(PageMode.A4, 1000, 1500, 18.0, 150)
        assertEquals(ImagePagePlanner.A4_WIDTH, p.pageWidth, eps)
        assertEquals(ImagePagePlanner.A4_HEIGHT, p.pageHeight, eps)
        assertTrue(p.rect.left >= 18.0 - eps && p.rect.bottom >= 18.0 - eps)
        assertTrue(p.rect.right <= p.pageWidth - 18.0 + eps && p.rect.top <= p.pageHeight - 18.0 + eps)
        assertEquals(1000.0 / 1500.0, p.rect.width / p.rect.height, 1e-9)
        assertEquals(p.rect.left, p.pageWidth - p.rect.right, eps)
        assertEquals(p.rect.bottom, p.pageHeight - p.rect.top, eps)
        // the taller side is the limit here: it fills the box height
        assertEquals(p.pageHeight - 36.0, p.rect.height, 1e-6)
    }

    @Test
    fun widePictureGetsALandscapePage() {
        val p = ImagePagePlanner.plan(PageMode.A4, 1500, 1000, 18.0, 150)
        assertEquals(ImagePagePlanner.A4_HEIGHT, p.pageWidth, eps)
        assertEquals(ImagePagePlanner.A4_WIDTH, p.pageHeight, eps)
        assertEquals(p.pageWidth - 36.0, p.rect.width, 1e-6)
    }

    @Test
    fun squarePictureStaysPortrait() {
        val p = ImagePagePlanner.plan(PageMode.LETTER, 800, 800, 0.0, 150)
        assertEquals(612.0, p.pageWidth, eps)
        assertEquals(792.0, p.pageHeight, eps)
        assertEquals(612.0, p.rect.width, 1e-6)
    }

    @Test
    fun smallPicturesAreScaledUpToFillTheBox() {
        val p = ImagePagePlanner.plan(PageMode.A4, 10, 10, 0.0, 150)
        assertEquals(ImagePagePlanner.A4_WIDTH, p.rect.width, 1e-6)
    }

    @Test
    fun matchImageUsesThePictureSizeAtTheGivenResolutionAndNoMargin() {
        val p = ImagePagePlanner.plan(PageMode.MATCH_IMAGE, 1500, 1000, 99.0, 150)
        assertEquals(720.0, p.pageWidth, eps)
        assertEquals(480.0, p.pageHeight, eps)
        assertEquals(0.0, p.rect.left, eps); assertEquals(0.0, p.rect.bottom, eps)
        assertEquals(720.0, p.rect.right, eps); assertEquals(480.0, p.rect.top, eps)
    }

    @Test
    fun matchDpiIsClamped() {
        assertEquals(1000.0 * 72.0 / 600.0, ImagePagePlanner.plan(PageMode.MATCH_IMAGE, 1000, 1000, 0.0, 5000).pageWidth, eps)
        assertEquals(1000.0 * 72.0 / 36.0, ImagePagePlanner.plan(PageMode.MATCH_IMAGE, 1000, 1000, 0.0, 1).pageWidth, eps)
    }

    @Test
    fun enormousPagesAreScaledDownToThePdfLimit() {
        val p = ImagePagePlanner.plan(PageMode.MATCH_IMAGE, 100000, 1000, 0.0, 36)
        assertEquals(ImagePagePlanner.MAX_PAGE_POINTS, p.pageWidth, 1e-6)
        assertTrue(p.pageHeight >= 1.0)
    }

    @Test
    fun anAbsurdMarginIsLimitedSoThePictureStillHasRoom() {
        val p = ImagePagePlanner.plan(PageMode.A4, 1000, 1500, 100000.0, 150)
        assertTrue(p.rect.width > 10.0 && p.rect.height > 10.0)
    }

    @Test
    fun degenerateSizesNeverGiveNaN() {
        for (mode in PageMode.values()) {
            val p = ImagePagePlanner.plan(mode, 0, 0, 18.0, 150)
            assertTrue(p.pageWidth.isFinite() && p.pageHeight.isFinite() && p.pageWidth >= 1.0)
            assertTrue(p.rect.width.isFinite() && p.rect.height.isFinite())
        }
    }
}
