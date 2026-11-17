package com.propdf.viewer.ui

import com.propdf.viewer.ui.PageLayoutEngine.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutEngineTest {

    private val eps = 0.001f
    private val gap = 10f

    private fun sizes(vararg wh: Pair<Float, Float>) = wh.map { PageSize(it.first, it.second) }
    private fun same(n: Int, w: Float = 100f, h: Float = 200f) = List(n) { PageSize(w, h) }

    // ---- VERTICAL -----------------------------------------------------------------------

    @Test
    fun verticalMatchesTheOriginalColumnFormula() {
        val input = sizes(600f to 800f, 800f to 600f, 300f to 500f)
        val result = PageLayoutEngine.compute(input, PageLayoutMode.VERTICAL, 16f)

        // Independent re-implementation of the pre-engine loop.
        val baseline = 600f
        var top = 0f
        input.forEachIndexed { i, s ->
            val docHeight = s.heightPts * (baseline / s.widthPts)
            val layout = result.layouts[i]
            assertEquals(i, layout.index)
            assertEquals(top, layout.docTop, eps)
            assertEquals(docHeight, layout.docHeight, eps)
            assertEquals(0f, layout.docLeft, eps)
            assertEquals(baseline, layout.docWidth, eps)
            top += docHeight + 16f
        }
        assertEquals(baseline, result.contentWidth, eps)
        assertEquals(top - 16f, result.contentHeight, eps)
    }

    @Test
    fun verticalKeepsRealPageSizesInTheLayout() {
        val result = PageLayoutEngine.compute(sizes(612f to 792f), PageLayoutMode.VERTICAL, gap)
        assertEquals(612f, result.layouts[0].widthPts, eps)
        assertEquals(792f, result.layouts[0].heightPts, eps)
    }

    // ---- TWO_PAGE -----------------------------------------------------------------------

    @Test
    fun twoPageWithCoverPutsPageOneAloneOnTheRight() {
        val r = PageLayoutEngine.compute(same(5), PageLayoutMode.TWO_PAGE, gap, coverAlone = true)
        assertEquals(210f, r.contentWidth, eps)
        assertEquals(620f, r.contentHeight, eps)
        val expected = listOf(
            Triple(110f, 0f, 0), Triple(0f, 210f, 1), Triple(110f, 210f, 2), Triple(0f, 420f, 3), Triple(110f, 420f, 4)
        )
        expected.forEach { (left, top, index) ->
            val l = r.layouts[index]
            assertEquals("left of page $index", left, l.docLeft, eps)
            assertEquals("top of page $index", top, l.docTop, eps)
            assertEquals(100f, l.docWidth, eps)
        }
    }

    @Test
    fun twoPageWithoutCoverPairsFromThePageOne() {
        val r = PageLayoutEngine.compute(same(5), PageLayoutMode.TWO_PAGE, gap, coverAlone = false)
        assertEquals(620f, r.contentHeight, eps)
        val lefts = r.layouts.map { it.docLeft }
        assertEquals(listOf(0f, 110f, 0f, 110f, 0f), lefts)
        val tops = r.layouts.map { it.docTop }
        assertEquals(listOf(0f, 0f, 210f, 210f, 420f), tops)
    }

    @Test
    fun twoPageRowHeightIsTheTallerPageAndPagesStayTopAligned() {
        val r = PageLayoutEngine.compute(sizes(100f to 200f, 100f to 300f, 100f to 100f), PageLayoutMode.TWO_PAGE, gap, coverAlone = false)
        assertEquals(0f, r.layouts[0].docTop, eps)
        assertEquals(0f, r.layouts[1].docTop, eps)
        assertEquals(200f, r.layouts[0].docHeight, eps)
        assertEquals(300f, r.layouts[1].docHeight, eps)
        assertEquals(310f, r.layouts[2].docTop, eps)
        assertEquals(410f, r.contentHeight, eps)
    }

    @Test
    fun twoPageSinglePageWithCoverIsStillOnTheRight() {
        val r = PageLayoutEngine.compute(same(1), PageLayoutMode.TWO_PAGE, gap, coverAlone = true)
        assertEquals(110f, r.layouts[0].docLeft, eps)
        assertEquals(210f, r.contentWidth, eps)
        assertEquals(200f, r.contentHeight, eps)
    }

    @Test
    fun twoPageScalesMixedSizesToTheSharedPageWidth() {
        val r = PageLayoutEngine.compute(sizes(100f to 200f, 200f to 200f), PageLayoutMode.TWO_PAGE, gap, coverAlone = false)
        assertEquals(100f, r.layouts[1].docWidth, eps)
        assertEquals(100f, r.layouts[1].docHeight, eps)
    }

    // ---- HORIZONTAL ---------------------------------------------------------------------

    @Test
    fun horizontalLaysPagesOutLeftToRightAtOneHeight() {
        val r = PageLayoutEngine.compute(sizes(100f to 200f, 200f to 200f), PageLayoutMode.HORIZONTAL, gap)
        assertEquals(200f, r.contentHeight, eps)
        assertEquals(310f, r.contentWidth, eps)
        assertEquals(0f, r.layouts[0].docLeft, eps)
        assertEquals(100f, r.layouts[0].docWidth, eps)
        assertEquals(110f, r.layouts[1].docLeft, eps)
        assertEquals(200f, r.layouts[1].docWidth, eps)
        r.layouts.forEach {
            assertEquals(0f, it.docTop, eps)
            assertEquals(200f, it.docHeight, eps)
        }
    }

    @Test
    fun horizontalScalesPagesOfOtherHeightsToTheFirstPageHeight() {
        val r = PageLayoutEngine.compute(sizes(100f to 200f, 100f to 100f), PageLayoutMode.HORIZONTAL, gap)
        assertEquals(200f, r.layouts[1].docWidth, eps)
        assertEquals(200f, r.layouts[1].docHeight, eps)
        assertEquals(310f, r.contentWidth, eps)
    }

    // ---- edge cases ---------------------------------------------------------------------

    @Test
    fun noPagesGivesAnEmptyLayoutInEveryMode() {
        PageLayoutMode.values().forEach { mode ->
            val r = PageLayoutEngine.compute(emptyList(), mode, gap)
            assertTrue(r.layouts.isEmpty())
            assertEquals(0f, r.contentWidth, eps)
            assertEquals(0f, r.contentHeight, eps)
        }
    }

    @Test
    fun zeroSizedPagesDoNotProduceNaNOrInfinity() {
        PageLayoutMode.values().forEach { mode ->
            val r = PageLayoutEngine.compute(sizes(0f to 0f, 0f to 0f), mode, gap)
            r.layouts.forEach {
                assertTrue(it.docTop.isFinite() && it.docLeft.isFinite() && it.docWidth.isFinite() && it.docHeight.isFinite())
            }
            assertTrue(r.contentWidth.isFinite() && r.contentHeight.isFinite())
        }
    }

    // ---- nearest page -------------------------------------------------------------------

    @Test
    fun verticalNearestPageIgnoresTheHorizontalCentre() {
        val r = PageLayoutEngine.compute(same(3), PageLayoutMode.VERTICAL, gap)
        // Page 2 spans y = 210..410, centre 310.
        assertEquals(1, PageLayoutEngine.nearestPage(r.layouts, PageLayoutMode.VERTICAL, 9999f, 300f)?.index)
        assertEquals(0, PageLayoutEngine.nearestPage(r.layouts, PageLayoutMode.VERTICAL, -9999f, 0f)?.index)
    }

    @Test
    fun horizontalNearestPageUsesTheHorizontalCentre() {
        val r = PageLayoutEngine.compute(same(3), PageLayoutMode.HORIZONTAL, gap)
        // Page 3 spans x = 220..320, centre 270.
        assertEquals(2, PageLayoutEngine.nearestPage(r.layouts, PageLayoutMode.HORIZONTAL, 260f, -500f)?.index)
    }

    @Test
    fun twoPageNearestPageChoosesBetweenLeftAndRight() {
        val r = PageLayoutEngine.compute(same(5), PageLayoutMode.TWO_PAGE, gap, coverAlone = true)
        // Row 2 is y = 210..410; left page (index 1) centre x = 50, right page (index 2) centre x = 160.
        assertEquals(1, PageLayoutEngine.nearestPage(r.layouts, PageLayoutMode.TWO_PAGE, 50f, 310f)?.index)
        assertEquals(2, PageLayoutEngine.nearestPage(r.layouts, PageLayoutMode.TWO_PAGE, 160f, 310f)?.index)
    }

    @Test
    fun nearestPageOfNothingIsNull() {
        assertNull(PageLayoutEngine.nearestPage(emptyList(), PageLayoutMode.VERTICAL, 0f, 0f))
    }

    // ---- scroll targets -----------------------------------------------------------------

    @Test
    fun verticalAndTwoPageTargetsScrollOnlyVertically() {
        val v = PageLayoutEngine.compute(same(3), PageLayoutMode.VERTICAL, gap)
        val t = PageLayoutEngine.scrollTargetFor(v.layouts[2], PageLayoutMode.VERTICAL, v.contentWidth, 100f)
        assertNull(t.x)
        assertEquals(420f, t.y, eps)

        val two = PageLayoutEngine.compute(same(5), PageLayoutMode.TWO_PAGE, gap)
        val t2 = PageLayoutEngine.scrollTargetFor(two.layouts[3], PageLayoutMode.TWO_PAGE, two.contentWidth, 100f)
        assertNull(t2.x)
        assertEquals(420f, t2.y, eps)
    }

    @Test
    fun horizontalTargetAlignsThePageLeftAndNeverScrollsPastTheEnd() {
        val r = PageLayoutEngine.compute(same(3), PageLayoutMode.HORIZONTAL, gap) // content width 320
        val first = PageLayoutEngine.scrollTargetFor(r.layouts[0], PageLayoutMode.HORIZONTAL, r.contentWidth, 150f)
        assertEquals(0f, first.x!!, eps)
        assertEquals(0f, first.y, eps)
        val last = PageLayoutEngine.scrollTargetFor(r.layouts[2], PageLayoutMode.HORIZONTAL, r.contentWidth, 150f)
        assertEquals(170f, last.x!!, eps) // 320 - 150, not the page's own left edge of 220
        val narrow = PageLayoutEngine.scrollTargetFor(r.layouts[2], PageLayoutMode.HORIZONTAL, r.contentWidth, 50f)
        assertEquals(220f, narrow.x!!, eps)
        val wide = PageLayoutEngine.scrollTargetFor(r.layouts[1], PageLayoutMode.HORIZONTAL, r.contentWidth, 1000f)
        assertEquals(0f, wide.x!!, eps)
    }

    @Test
    fun modeNamesRoundTripAndUnknownFallsBackToVertical() {
        PageLayoutMode.values().forEach { assertEquals(it, PageLayoutMode.fromName(it.name)) }
        assertEquals(PageLayoutMode.VERTICAL, PageLayoutMode.fromName(null))
        assertEquals(PageLayoutMode.VERTICAL, PageLayoutMode.fromName("BOOK"))
        assertNotNull(PageLayoutMode.values())
    }
}
