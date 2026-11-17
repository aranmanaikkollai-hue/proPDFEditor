package com.propdf.viewer.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How pages are arranged in the viewer. */
enum class PageLayoutMode {
    /** One column, scrolled up and down. */
    VERTICAL,

    /** Two pages side by side per row, scrolled up and down. */
    TWO_PAGE,

    /** One row, scrolled left and right. */
    HORIZONTAL;

    companion object {
        fun fromName(name: String?): PageLayoutMode = values().firstOrNull { it.name == name } ?: VERTICAL
    }
}

/**
 * Pure page-geometry code for the viewer, free of Android classes so it can be unit tested on the JVM.
 *
 * All positions are in "document units": PDF points scaled so that every page shares one common width
 * (VERTICAL, TWO_PAGE) or one common height (HORIZONTAL), keeping each page's own aspect ratio.
 */
object PageLayoutEngine {

    data class PageSize(val widthPts: Float, val heightPts: Float)

    data class Result(
        val layouts: List<PDFViewerViewModel.PageLayout>,
        val contentWidth: Float,
        val contentHeight: Float
    )

    /** Scroll position that brings a page into view. A null [x] means "leave the horizontal position alone". */
    data class ScrollTarget(val x: Float?, val y: Float)

    fun compute(
        sizes: List<PageSize>,
        mode: PageLayoutMode,
        gap: Float,
        coverAlone: Boolean = true
    ): Result {
        if (sizes.isEmpty()) return Result(emptyList(), 0f, 0f)
        return when (mode) {
            PageLayoutMode.VERTICAL -> vertical(sizes, gap)
            PageLayoutMode.TWO_PAGE -> twoPage(sizes, gap, coverAlone)
            PageLayoutMode.HORIZONTAL -> horizontal(sizes, gap)
        }
    }

    private fun vertical(sizes: List<PageSize>, gap: Float): Result {
        val baseline = sizes[0].widthPts.coerceAtLeast(1f)
        val layouts = ArrayList<PDFViewerViewModel.PageLayout>(sizes.size)
        var top = 0f
        for ((i, s) in sizes.withIndex()) {
            val docHeight = s.heightPts * (baseline / s.widthPts.coerceAtLeast(1f))
            layouts.add(
                PDFViewerViewModel.PageLayout(
                    index = i, widthPts = s.widthPts, heightPts = s.heightPts,
                    docTop = top, docHeight = docHeight, docLeft = 0f, docWidth = baseline
                )
            )
            top += docHeight + gap
        }
        return Result(layouts, baseline, (top - gap).coerceAtLeast(0f))
    }

    /**
     * Rows of two pages. With [coverAlone] page 1 sits alone on the right-hand side of the first row (like the
     * cover of a book) and the rest pair up as 2-3, 4-5, and so on; otherwise pages pair up as 1-2, 3-4. A last
     * page without a partner sits on the left.
     */
    private fun twoPage(sizes: List<PageSize>, gap: Float, coverAlone: Boolean): Result {
        val baseline = sizes[0].widthPts.coerceAtLeast(1f)
        val rightLeft = baseline + gap
        val layouts = ArrayList<PDFViewerViewModel.PageLayout>(sizes.size)

        fun docHeightOf(s: PageSize) = s.heightPts * (baseline / s.widthPts.coerceAtLeast(1f))
        fun place(index: Int, left: Float, top: Float) {
            val s = sizes[index]
            layouts.add(
                PDFViewerViewModel.PageLayout(
                    index = index, widthPts = s.widthPts, heightPts = s.heightPts,
                    docTop = top, docHeight = docHeightOf(s), docLeft = left, docWidth = baseline
                )
            )
        }

        var top = 0f
        var i = 0
        if (coverAlone) {
            place(0, rightLeft, top)
            top += docHeightOf(sizes[0]) + gap
            i = 1
        }
        while (i < sizes.size) {
            val hasPartner = i + 1 < sizes.size
            val rowHeight = max(docHeightOf(sizes[i]), if (hasPartner) docHeightOf(sizes[i + 1]) else 0f)
            place(i, 0f, top)
            if (hasPartner) place(i + 1, rightLeft, top)
            top += rowHeight + gap
            i += 2
        }
        return Result(layouts, 2f * baseline + gap, (top - gap).coerceAtLeast(0f))
    }

    private fun horizontal(sizes: List<PageSize>, gap: Float): Result {
        val baselineHeight = sizes[0].heightPts.coerceAtLeast(1f)
        val layouts = ArrayList<PDFViewerViewModel.PageLayout>(sizes.size)
        var left = 0f
        for ((i, s) in sizes.withIndex()) {
            val docWidth = s.widthPts.coerceAtLeast(1f) * (baselineHeight / s.heightPts.coerceAtLeast(1f))
            layouts.add(
                PDFViewerViewModel.PageLayout(
                    index = i, widthPts = s.widthPts, heightPts = s.heightPts,
                    docTop = 0f, docHeight = baselineHeight, docLeft = left, docWidth = docWidth
                )
            )
            left += docWidth + gap
        }
        return Result(layouts, (left - gap).coerceAtLeast(0f), baselineHeight)
    }

    /**
     * Distance from a page's centre to the viewport centre ([centerX], [centerY], document units), measured
     * along the scroll axis for VERTICAL and HORIZONTAL and in both axes for TWO_PAGE.
     */
    fun distanceToCenter(
        layout: PDFViewerViewModel.PageLayout,
        mode: PageLayoutMode,
        centerX: Float,
        centerY: Float
    ): Float {
        val pageCenterY = layout.docTop + layout.docHeight / 2f
        val pageCenterX = layout.docLeft + layout.docWidth / 2f
        return when (mode) {
            PageLayoutMode.VERTICAL -> abs(pageCenterY - centerY)
            PageLayoutMode.HORIZONTAL -> abs(pageCenterX - centerX)
            PageLayoutMode.TWO_PAGE -> hypot(pageCenterX - centerX, pageCenterY - centerY)
        }
    }

    fun nearestPage(
        layouts: List<PDFViewerViewModel.PageLayout>,
        mode: PageLayoutMode,
        centerX: Float,
        centerY: Float
    ): PDFViewerViewModel.PageLayout? =
        layouts.minByOrNull { distanceToCenter(it, mode, centerX, centerY) }

    /**
     * Scroll position that brings [layout] into view. VERTICAL and TWO_PAGE scroll only vertically (horizontal
     * position is a pixel pan inside the canvas, so [ScrollTarget.x] is null); HORIZONTAL scrolls only
     * horizontally and never past the end of the content.
     */
    fun scrollTargetFor(
        layout: PDFViewerViewModel.PageLayout,
        mode: PageLayoutMode,
        contentWidth: Float,
        viewportWidthPts: Float
    ): ScrollTarget = when (mode) {
        PageLayoutMode.HORIZONTAL ->
            ScrollTarget(layout.docLeft.coerceIn(0f, (contentWidth - viewportWidthPts).coerceAtLeast(0f)), 0f)
        else -> ScrollTarget(null, layout.docTop)
    }
}
