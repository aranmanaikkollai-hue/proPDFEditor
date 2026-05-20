package com.propdf.viewer.coords

import android.graphics.Matrix
import android.graphics.PointF

/**
 * Shared coordinate transformation system between PDF rendering and annotations.
 * Converts between screen pixels, bitmap pixels, and normalized PDF coordinates (0.0–1.0).
 *
 * Owned by PremiumPageView and updated whenever zoom/pan changes.
 */
class PdfCoordinateSpace {

    private val pdfToScreen = Matrix()
    private val screenToPdf = Matrix()
    private val tempPoint = FloatArray(2)

    private var bitmapWidth: Float = 1f
    private var bitmapHeight: Float = 1f

    /**
     * Update matrices from the current PDF view transform.
     * @param transformMatrix The PremiumPageView canvas transform (bitmap space → screen space)
     * @param bitmapW Width of the rendered PDF bitmap in pixels
     * @param bitmapH Height of the rendered PDF bitmap in pixels
     */
    fun update(transformMatrix: Matrix, bitmapW: Float, bitmapH: Float) {
        bitmapWidth = bitmapW.coerceAtLeast(1f)
        bitmapHeight = bitmapH.coerceAtLeast(1f)

        val scaleMatrix = Matrix().apply { setScale(bitmapWidth, bitmapHeight) }
        pdfToScreen.set(transformMatrix)
        pdfToScreen.postConcat(scaleMatrix)
        pdfToScreen.invert(screenToPdf)
    }

    /** Convert normalized PDF coordinates to screen pixels. */
    fun pdfToScreen(nx: Float, ny: Float): PointF {
        tempPoint[0] = nx
        tempPoint[1] = ny
        pdfToScreen.mapPoints(tempPoint)
        return PointF(tempPoint[0], tempPoint[1])
    }

    /** Convert screen pixels to normalized PDF coordinates (0.0–1.0). */
    fun screenToPdf(screenX: Float, screenY: Float): PointF {
        tempPoint[0] = screenX
        tempPoint[1] = screenY
        screenToPdf.mapPoints(tempPoint)
        return PointF(
            (tempPoint[0] / bitmapWidth).coerceIn(0f, 1f),
            (tempPoint[1] / bitmapHeight).coerceIn(0f, 1f)
        )
    }

    fun getBitmapWidth(): Float = bitmapWidth
    fun getBitmapHeight(): Float = bitmapHeight
}
