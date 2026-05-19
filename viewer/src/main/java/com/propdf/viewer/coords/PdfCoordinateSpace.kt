package com.propdf.viewer.coords
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
/**
* Central coordinate conversion system.
* ALL coordinates stored in PDF space (0..1 normalized or page points).
* Conversion to screen pixels happens at render time via shared Matrix.
*
* This eliminates desync by ensuring annotations and PDF use the same transform.
*/
class PdfCoordinateSpace(
val pageWidthPoints: Float,
val pageHeightPoints: Float
) {
// Shared transform matrix - single source of truth
private val pdfToScreenMatrix = Matrix()
private val screenToPdfMatrix = Matrix()
// Cached values to avoid matrix inversion on every touch
private var cachedScale = 1f
private var cachedOffsetX = 0f
private var cachedOffsetY = 0f
/**
* Update the shared transform. Called once per frame by PremiumPageView.
* All annotation rendering uses this same matrix.
*/
fun updateTransform(
viewWidth: Int,
viewHeight: Int,
scale: Float,
translateX: Float,
translateY: Float
) {
pdfToScreenMatrix.reset()
// PDF origin (0,0) at top-left of page content area
pdfToScreenMatrix.postScale(scale, scale)
pdfToScreenMatrix.postTranslate(translateX, translateY)
// Cache for fast inverse without matrix math
cachedScale = scale
cachedOffsetX = translateX
cachedOffsetY = translateY
// Build inverse for touch conversion
screenToPdfMatrix.reset()
screenToPdfMatrix.postTranslate(-translateX, -translateY)
screenToPdfMatrix.postScale(1f / scale, 1f / scale)
}
/** Convert PDF point (in page points) to screen pixels */
fun pdfToScreen(pdfX: Float, pdfY: Float): PointF {
val pts = floatArrayOf(pdfX, pdfY)
pdfToScreenMatrix.mapPoints(pts)
return PointF(pts[0], pts[1])
}
/** Convert screen pixel to PDF point */
fun screenToPdf(screenX: Float, screenY: Float): PointF {
val pts = floatArrayOf(screenX, screenY)
screenToPdfMatrix.mapPoints(pts)
return PointF(pts[0], pts[1])
}
/** Convert normalized PDF coords (0..1) to screen pixels */
fun normalizedToScreen(nx: Float, ny: Float): PointF {
return pdfToScreen(nx * pageWidthPoints, ny * pageHeightPoints)
}
/** Convert screen pixels to normalized PDF coords (0..1) */
fun screenToNormalized(screenX: Float, screenY: Float): PointF {
val pdf = screenToPdf(screenX, screenY)
return PointF(pdf.x / pageWidthPoints, pdf.y / pageHeightPoints)
}
/** Convert PDF rect (in page points) to screen rect */
fun pdfRectToScreen(pdfRect: RectF): RectF {
val dst = RectF()
pdfToScreenMatrix.mapRect(dst, pdfRect)
return dst
}
/** Convert normalized rect (0..1) to screen rect */
fun normalizedRectToScreen(nx: Float, ny: Float, nw: Float, nh: Float): RectF {
val pdfRect = RectF(
nx * pageWidthPoints,
ny * pageHeightPoints,
(nx + nw) * pageWidthPoints,
(ny + nh) * pageHeightPoints
)
return pdfRectToScreen(pdfRect)
}
/** Get current scale factor */
fun getScale(): Float = cachedScale
/** Get the shared transform matrix (for canvas.concat) */
fun getTransformMatrix(): Matrix = Matrix(pdfToScreenMatrix)
/** Check if point is within page bounds on screen */
fun isWithinPage(screenX: Float, screenY: Float): Boolean {
val pdf = screenToPdf(screenX, screenY)
return pdf.x in 0f..pageWidthPoints && pdf.y in 0f..pageHeightPoints
}
}
