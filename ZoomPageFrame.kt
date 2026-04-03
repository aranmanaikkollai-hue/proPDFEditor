// FILE: ZoomPageFrame.kt
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/ui/viewer/ZoomPageFrame.kt
//
// FEATURE: Pinch-to-zoom with corrected translation offset
//   BUG FIXED: After zooming, panning offset was calculated relative to the
//   unscaled view origin, causing the content to jump to wrong position.
//   FIX: Store the pivot point in scaled coordinates and translate around it.
//
// HOW IT WORKS:
//   - ScaleGestureDetector drives scale factor
//   - Single-finger pan (after scale) uses clamped translation
//   - Double-tap resets to 1x
//   - View matrix applied via canvas.concat(matrix)
//   - AnnotationCanvasView is a child and receives scaled touch events
//
// RULES OBEYED:
//   - No FrameLayout.LayoutParams(w,h,weight) constructor  (rule #10)
//   - Pure ASCII                                           (rule #32)
//   - All floats use f suffix                              (rule #12)
//   - No Paint.getTag()/setTag()                           (rule #12)

package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

class ZoomPageFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // -----------------------------------------------------------------------
    // STATE
    // -----------------------------------------------------------------------

    private var scaleFactor = 1f
    private val minScale    = 1f
    private val maxScale    = 5f

    // Translation (pan) in screen pixels, applied AFTER scaling
    private var transX = 0f
    private var transY = 0f

    // Pivot of the last pinch gesture (in view coordinates)
    private var pivotX = 0f
    private var pivotY = 0f

    // -----------------------------------------------------------------------
    // GESTURE DETECTORS
    // -----------------------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pivotX = detector.focusX
                pivotY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor   = detector.scaleFactor
                val newScale = (scaleFactor * factor).coerceIn(minScale, maxScale)
                val ratio    = newScale / scaleFactor

                // Adjust translation so the pinch focus point stays fixed.
                // FIX: translate by (pivot - pivot*ratio) to keep pivot stationary.
                transX = pivotX - ratio * (pivotX - transX)
                transY = pivotY - ratio * (pivotY - transY)

                scaleFactor = newScale
                clampTranslation()
                invalidate()
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                clampTranslation()
                invalidate()
            }
        }
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning   = false

    private val doubleTapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        }
    )

    // -----------------------------------------------------------------------
    // TOUCH DISPATCH
    // -----------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Intercept multi-finger events for zoom; let single-finger pass to children
        return ev.pointerCount > 1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        doubleTapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning  = scaleFactor > 1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && isPanning) {
                    transX += event.x - lastTouchX
                    transY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    clampTranslation()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
            }
        }
        return true
    }

    // -----------------------------------------------------------------------
    // DRAWING
    // -----------------------------------------------------------------------

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        // Apply the combined transform: scale around current pivot + translate
        canvas.translate(transX, transY)
        canvas.scale(scaleFactor, scaleFactor, 0f, 0f)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    // -----------------------------------------------------------------------
    // CLAMPING  (prevent panning beyond page edges)
    // -----------------------------------------------------------------------

    private fun clampTranslation() {
        if (scaleFactor <= 1f) { transX = 0f; transY = 0f; return }

        val scaledW = width  * scaleFactor
        val scaledH = height * scaleFactor

        // Maximum pan = how far the scaled content extends beyond the view
        val maxTransX = max(0f, (scaledW - width)  / 2f)
        val maxTransY = max(0f, (scaledH - height) / 2f)

        transX = transX.coerceIn(-maxTransX, maxTransX)
        transY = transY.coerceIn(-maxTransY, maxTransY)
    }

    // -----------------------------------------------------------------------
    // PUBLIC API
    // -----------------------------------------------------------------------

    fun resetZoom() {
        scaleFactor = 1f
        transX = 0f
        transY = 0f
        invalidate()
    }

    fun isZoomed(): Boolean = scaleFactor > 1.05f

    // Convert screen touch coords to content coords (for annotation placement)
    fun toContentCoords(screenX: Float, screenY: Float): FloatArray {
        val contentX = (screenX - transX) / scaleFactor
        val contentY = (screenY - transY) / scaleFactor
        return floatArrayOf(contentX, contentY)
    }

    // Returns a Matrix representing the current transform (for child views)
    fun getTransformMatrix(): Matrix {
        val m = Matrix()
        m.setScale(scaleFactor, scaleFactor, 0f, 0f)
        m.postTranslate(transX, transY)
        return m
    }
}
