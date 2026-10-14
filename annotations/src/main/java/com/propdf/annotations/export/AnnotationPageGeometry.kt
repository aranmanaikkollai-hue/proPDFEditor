package com.propdf.annotations.export

/**
 * Pure page geometry (no Android, no PDFBox) for flattening overlay annotations.
 *
 * Conventions:
 *  - Overlay annotation coordinates are in DISPLAY space: points, origin at the top-left of the page as the
 *    reader sees it (after /Rotate, inside the CropBox), y growing downwards. The exporter's drawing code
 *    flips y with `displayHeight - y`, so it works in "bottom-left display space" of size displayW x displayH.
 *  - [displayToUser] maps that bottom-left display space to PDF user space for a page with clockwise /Rotate
 *    [rotation] and visible box (llx, lly, width, height). It is applied ONCE per page, before any annotation is drawn.
 *
 * The matrices mirror app's PdfPageGeometry and core's VisualSignatureGeometry (kept separate because those
 * live in other modules and are not public; consolidation belongs to the Phase 3 coordinate-system design).
 */
object AnnotationPageGeometry {

    fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    fun displayWidth(rotation: Int, boxWidth: Float, boxHeight: Float): Float =
        if (normalizeRotation(rotation) % 180 == 0) boxWidth else boxHeight

    fun displayHeight(rotation: Int, boxWidth: Float, boxHeight: Float): Float =
        if (normalizeRotation(rotation) % 180 == 0) boxHeight else boxWidth

    /** Affine matrix [a, b, c, d, e, f] (PDF order) mapping display space to user space. */
    fun displayToUser(rotation: Int, llx: Float, lly: Float, width: Float, height: Float): FloatArray =
        when (normalizeRotation(rotation)) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + width, lly)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + width, lly + height)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + height)
            else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
        }

    /** Applies [displayToUser] to a point (used by tests to check where an annotation lands). */
    fun mapPoint(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
}
