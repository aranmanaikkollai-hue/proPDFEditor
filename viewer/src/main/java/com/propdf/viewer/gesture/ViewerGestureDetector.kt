package com.propdf.viewer.gesture

import android.content.Context
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Premium gesture engine for PDF viewer.
 * Handles pinch-zoom, pan, fling, double-tap, and multi-touch gestures.
 * Features:
 * - Scale smoothing window to eliminate zoom jitter
 * - Momentum-based fling with OverScroller
 * - Configurable zoom levels for double-tap
 * - Proper touch event interception handling
 */
class ViewerGestureDetector(
    context: Context,
    private val listener: GestureListener
) : View.OnTouchListener {

    interface GestureListener {
        fun onScale(scaleFactor: Float, focusX: Float, focusY: Float)
        fun onScaleEnd()
        fun onScroll(deltaX: Float, deltaY: Float)
        fun onFling(velocityX: Float, velocityY: Float)
        fun onSingleTap(x: Float, y: Float)
        fun onDoubleTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onTouchDown()
        fun onTouchUp()
    }

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                listener.onTouchDown()
                scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener.onSingleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                listener.onDoubleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                listener.onLongPress(e.x, e.y)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isScaling) {
                    listener.onScroll(distanceX, distanceY)
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!isScaling) {
                    listener.onFling(velocityX, velocityY)
                }
                return true
            }
        }).apply {
        setIsLongpressEnabled(true)
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                scaleStartFocus.set(detector.focusX, detector.focusY)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val smoothedFactor = smoothScaleFactor(factor)
                listener.onScale(smoothedFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                listener.onScaleEnd()
            }
        })

    private val scroller = OverScroller(context, DecelerateInterpolator(1.5f))
    private val scaleStartFocus = PointF()
    private var isScaling = false
    private var lastScaleTime = 0L
    private val scaleSmoothingWindow = FloatArray(3) { 1f }
    private var scaleWindowIndex = 0

    // Configuration
    private val minZoom = 0.25f
    private val maxZoom = 10.0f
    private val doubleTapZoomLevels = floatArrayOf(1.0f, 2.5f, 5.0f)
    private var currentZoomIndex = 0

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener.onTouchUp()
            }
        }

        var handled = scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }
        return handled
    }

    private fun smoothScaleFactor(rawFactor: Float): Float {
        val now = System.currentTimeMillis()
        if (now - lastScaleTime > 100) {
            scaleWindowIndex = 0
            scaleSmoothingWindow.fill(1f)
        }
        lastScaleTime = now

        scaleSmoothingWindow[scaleWindowIndex % scaleSmoothingWindow.size] = rawFactor
        scaleWindowIndex++

        var sum = 0f
        var weightSum = 0f
        for (i in scaleSmoothingWindow.indices) {
            val weight = (i + 1).toFloat()
            sum += scaleSmoothingWindow[i] * weight
            weightSum += weight
        }
        return sum / weightSum
    }

    fun computeScroll(): Boolean {
        if (scroller.computeScrollOffset()) {
            return true
        }
        return false
    }

    fun fling(
        startX: Int, startY: Int,
        velocityX: Int, velocityY: Int,
        minX: Int, maxX: Int,
        minY: Int, maxY: Int
    ) {
        scroller.fling(
            startX, startY,
            velocityX, velocityY,
            minX, maxX,
            minY, maxY
        )
    }

    fun getNextDoubleTapZoom(currentZoom: Float): Float {
        currentZoomIndex = (currentZoomIndex + 1) % doubleTapZoomLevels.size
        return doubleTapZoomLevels[currentZoomIndex]
    }

    fun resetZoomIndex() {
        currentZoomIndex = 0
    }
}
