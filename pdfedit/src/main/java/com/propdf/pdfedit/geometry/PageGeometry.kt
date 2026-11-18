package com.propdf.pdfedit.geometry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one coordinate system of the editing engine: Page Display Space (PDS).
 *
 * PDS is measured in points (1/72 inch), with its origin at the bottom-left corner of the page as the user sees it
 * (the CropBox, after the page's clockwise /Rotate has been applied) and y pointing up. Every edit operation stores
 * geometry only in PDS. This class owns every conversion; nothing else in the engine converts coordinates.
 *
 * Pure Kotlin (no Android, no PDFBox), so it is unit tested on the JVM. The display-to-user matrices are the same as
 * the ones already used by app PdfPageGeometry, annotations AnnotationPageGeometry and core VisualSignatureGeometry.
 *
 * @param boxLeft the visible box (CropBox) in user-space units
 * @param rotation the page's /Rotate in degrees (any multiple of 90, negatives allowed)
 * @param userUnit the page's /UserUnit (size of one user-space unit in points, normally 1)
 */
class PageGeometry(
    boxLeft: Double,
    boxBottom: Double,
    boxRight: Double,
    boxTop: Double,
    rotation: Int,
    userUnit: Double = 1.0
) {
    val rotation: Int = ((((rotation / 90.0).roundToInt() * 90) % 360) + 360) % 360
    private val unit: Double = if (userUnit > 0.0) userUnit else 1.0
    private val left = minOf(boxLeft, boxRight)
    private val bottom = minOf(boxBottom, boxTop)
    private val boxW = abs(boxRight - boxLeft)
    private val boxH = abs(boxTop - boxBottom)

    /** Width of the displayed page in points. */
    val displayWidth: Double = (if (this.rotation % 180 == 0) boxW else boxH) * unit

    /** Height of the displayed page in points. */
    val displayHeight: Double = (if (this.rotation % 180 == 0) boxH else boxW) * unit

    /** Maps PDS points to PDF user space (what a content stream draws in). */
    val displayToUser: Matrix2D = Matrix2D.scale(1.0 / unit, 1.0 / unit).then(
        when (this.rotation) {
            90 -> Matrix2D(0.0, 1.0, -1.0, 0.0, left + boxW, bottom)
            180 -> Matrix2D(-1.0, 0.0, 0.0, -1.0, left + boxW, bottom + boxH)
            270 -> Matrix2D(0.0, -1.0, 1.0, 0.0, left, bottom + boxH)
            else -> Matrix2D(1.0, 0.0, 0.0, 1.0, left, bottom)
        }
    )

    /** Maps PDF user space to PDS. */
    val userToDisplay: Matrix2D = displayToUser.inverse() ?: Matrix2D.IDENTITY

    /** Where a user-space point (for example from a content stream or a PDFBox TextPosition) lies in PDS. */
    fun userToPds(x: Double, y: Double): PointPds = userToDisplay.transform(PointPds(x, y))

    /** Bounding box in PDS of the image that [userMatrix] places on the unit square in user space. */
    fun imagePlacementToPds(userMatrix: Matrix2D): RectPds {
        val corners = listOf(PointPds(0.0, 0.0), PointPds(1.0, 0.0), PointPds(1.0, 1.0), PointPds(0.0, 1.0))
            .map { userToDisplay.transform(userMatrix.transform(it)) }
        return RectPds.of(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    /** The user-space matrix that draws the unit square onto [bounds] (given in PDS). */
    fun placementMatrixFor(bounds: RectPds): Matrix2D = Matrix2D.unitSquareTo(bounds).then(displayToUser)

    /** Pixel (origin top-left, y down) of a PDS point in a bitmap rendered at [pxPerPoint] pixels per point. */
    fun pdsToBitmap(p: PointPds, pxPerPoint: Double): Pair<Double, Double> =
        Pair(p.x * pxPerPoint, (displayHeight - p.y) * pxPerPoint)

    /** PDS point under a pixel of a bitmap rendered at [pxPerPoint] pixels per point. */
    fun bitmapToPds(px: Double, py: Double, pxPerPoint: Double): PointPds =
        PointPds(px / pxPerPoint, displayHeight - py / pxPerPoint)
}
