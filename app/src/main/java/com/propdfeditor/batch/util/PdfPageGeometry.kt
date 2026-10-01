package com.propdfeditor.batch.util

/**
 * Pure page-geometry helpers (no Android / PDFBox dependency, JVM unit-testable).
 *
 * "Display space" = the page as the user sees it: origin bottom-left, upright, after the
 * page's /Rotate has been applied. Drawing code works in display space and prepends
 * [displayToUser] so the result is correct on rotated pages.
 */
object PdfPageGeometry {

    /** Normalises any multiple of 90 (including negatives) to 0, 90, 180 or 270. */
    fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    /** Width/height of the page as displayed (swapped for 90/270). */
    fun displaySize(rotation: Int, boxWidth: Float, boxHeight: Float): Pair<Float, Float> =
        if (normalizeRotation(rotation) % 180 == 0) boxWidth to boxHeight else boxHeight to boxWidth

    /**
     * Affine matrix [a, b, c, d, e, f] mapping display space to PDF user space for a page with
     * the given clockwise /Rotate and visible box (llx, lly, width, height).
     */
    fun displayToUser(
        rotation: Int,
        llx: Float,
        lly: Float,
        width: Float,
        height: Float
    ): FloatArray = when (normalizeRotation(rotation)) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + width, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + width, lly + height)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + height)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }
}
