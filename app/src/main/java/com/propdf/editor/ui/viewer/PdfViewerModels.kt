package com.propdf.editor.ui.viewer

import android.graphics.RectF

/** How pages are laid out and navigated. */
enum class PdfViewMode {
    VERTICAL_CONTINUOUS,
    HORIZONTAL_CONTINUOUS,
    SINGLE_PAGE,
    TWO_PAGE
}

/** Reading color scheme, applied as a color filter over rendered pages. */
enum class PdfColorMode {
    NORMAL,
    NIGHT,
    SEPIA
}

/** A single text search match on a page, in PDF point space. */
data class PdfSearchMatch(
    val pageIndex: Int,
    val rect: RectF,
    val snippet: String
)

/** Lightweight per-page metadata needed before any bitmap is rendered. */
data class PdfPageInfo(
    val index: Int,
    val widthPt: Float,
    val heightPt: Float
)

/** One entry in the page visit history (for Recent Pages / back-forward navigation). */
data class PdfHistoryEntry(
    val pageIndex: Int,
    val timestamp: Long = System.currentTimeMillis()
)
