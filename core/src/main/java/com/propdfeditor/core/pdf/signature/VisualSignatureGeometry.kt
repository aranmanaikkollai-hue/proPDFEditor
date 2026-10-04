package com.propdfeditor.core.pdf.signature

/**
 * Pure geometry (no Android, no PDFBox) for placing a VISUAL signature image on a page.
 *
 * Conventions:
 *  - The caller's rectangle is in DISPLAY space: points, origin at the TOP-LEFT of the page as the
 *    reader sees it (after /Rotate, inside the CropBox), y growing downwards. This matches android.graphics.RectF.
 *  - "User space" is the unrotated space the page content stream is written in.
 *  - Rotation is clockwise degrees, as in the PDF /Rotate entry.
 *
 * This is image placement only. Nothing here has anything to do with cryptographic signing.
 */
internal object VisualSignatureGeometry {

    /** Affine matrix [a b c d e f] in PDF order, for drawing a unit-square image into user space. */
    data class Placement(
        val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float,
        /** Display-space width/height actually used for the image (points). */
        val drawWidth: Float, val drawHeight: Float
    )

    fun normalizeRotation(degrees: Int): Int = Math.floorMod((degrees / 90) * 90, 360)

    fun displayWidth(boxWidth: Float, boxHeight: Float, rotation: Int): Float =
        if (rotation == 90 || rotation == 270) boxHeight else boxWidth

    fun displayHeight(boxWidth: Float, boxHeight: Float, rotation: Int): Float =
        if (rotation == 90 || rotation == 270) boxWidth else boxHeight

    /** Display point (dx right, dyUp upwards from the visible box's bottom-left) to user-space X. */
    fun toUserX(left: Float, boxWidth: Float, rotation: Int, dx: Float, dyUp: Float): Float = when (rotation) {
        0 -> left + dx
        90 -> left + (boxWidth - dyUp)
        180 -> left + (boxWidth - dx)
        else -> left + dyUp
    }

    /** Display point to user-space Y. */
    fun toUserY(bottom: Float, boxHeight: Float, rotation: Int, dx: Float, dyUp: Float): Float = when (rotation) {
        0 -> bottom + dyUp
        90 -> bottom + dx
        180 -> bottom + (boxHeight - dyUp)
        else -> bottom + (boxHeight - dx)
    }

    /**
     * Fits an image of [imgW] x [imgH] pixels inside the display rectangle (rectLeft, rectTop, rectRight,
     * rectBottom; top-left origin), keeping its aspect ratio and centring it. The rectangle is first clipped
     * to the visible page. Throws [IllegalArgumentException] for non-finite, empty or fully off-page input.
     */
    fun place(
        boxLeft: Float, boxBottom: Float, boxRight: Float, boxTop: Float, rotationDegrees: Int,
        rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
        imgW: Int, imgH: Int
    ): Placement {
        require(imgW > 0 && imgH > 0) { "Signature image is empty" }
        val values = floatArrayOf(boxLeft, boxBottom, boxRight, boxTop, rectLeft, rectTop, rectRight, rectBottom)
        require(values.all { it.isFinite() }) { "Signature area is not valid" }
        val boxW = boxRight - boxLeft
        val boxH = boxTop - boxBottom
        require(boxW > 0f && boxH > 0f) { "Page size is not valid" }

        val rot = normalizeRotation(rotationDegrees)
        val dispW = displayWidth(boxW, boxH, rot)
        val dispH = displayHeight(boxW, boxH, rot)

        val l = maxOf(0f, minOf(rectLeft, rectRight))
        val r = minOf(dispW, maxOf(rectLeft, rectRight))
        val t = maxOf(0f, minOf(rectTop, rectBottom))
        val b = minOf(dispH, maxOf(rectTop, rectBottom))
        require(r - l >= 1f && b - t >= 1f) { "Signature area is outside the page or too small" }

        val areaW = r - l
        val areaH = b - t
        val scale = minOf(areaW / imgW, areaH / imgH)
        val drawW = imgW * scale
        val drawH = imgH * scale
        val dx = l + (areaW - drawW) / 2f
        val topDown = t + (areaH - drawH) / 2f
        val dyUp = dispH - (topDown + drawH)

        val cos = when (rot) { 90 -> 0f; 180 -> -1f; 270 -> 0f; else -> 1f }
        val sin = when (rot) { 90 -> 1f; 180 -> 0f; 270 -> -1f; else -> 0f }
        return Placement(
            a = drawW * cos, b = drawW * sin, c = -drawH * sin, d = drawH * cos,
            e = toUserX(boxLeft, boxW, rot, dx, dyUp),
            f = toUserY(boxBottom, boxH, rot, dx, dyUp),
            drawWidth = drawW, drawHeight = drawH
        )
    }

    /** Axis-aligned rectangle in USER space: lower-left corner plus size. */
    data class UserRect(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * Maps a DISPLAY-space rectangle (top-left origin, clipped to the visible page) to the equivalent
     * rectangle in unrotated USER space. Used for the widget rectangle of a cryptographic signature field.
     */
    fun userRect(
        boxLeft: Float, boxBottom: Float, boxRight: Float, boxTop: Float, rotationDegrees: Int,
        rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float
    ): UserRect {
        val values = floatArrayOf(boxLeft, boxBottom, boxRight, boxTop, rectLeft, rectTop, rectRight, rectBottom)
        require(values.all { it.isFinite() }) { "Signature area is not valid" }
        val boxW = boxRight - boxLeft
        val boxH = boxTop - boxBottom
        require(boxW > 0f && boxH > 0f) { "Page size is not valid" }
        val rot = normalizeRotation(rotationDegrees)
        val dispW = displayWidth(boxW, boxH, rot)
        val dispH = displayHeight(boxW, boxH, rot)
        val l = maxOf(0f, minOf(rectLeft, rectRight))
        val r = minOf(dispW, maxOf(rectLeft, rectRight))
        val t = maxOf(0f, minOf(rectTop, rectBottom))
        val b = minOf(dispH, maxOf(rectTop, rectBottom))
        require(r - l >= 1f && b - t >= 1f) { "Signature area is outside the page or too small" }
        val x1 = toUserX(boxLeft, boxW, rot, l, dispH - t)
        val y1 = toUserY(boxBottom, boxH, rot, l, dispH - t)
        val x2 = toUserX(boxLeft, boxW, rot, r, dispH - b)
        val y2 = toUserY(boxBottom, boxH, rot, r, dispH - b)
        return UserRect(minOf(x1, x2), minOf(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1))
    }
}
