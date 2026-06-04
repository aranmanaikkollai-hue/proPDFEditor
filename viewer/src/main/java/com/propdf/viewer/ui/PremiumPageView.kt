package com.propdf.viewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.render.UnifiedAnnotationRenderer
import com.propdf.viewer.coords.PdfCoordinateSpace
import com.propdf.viewer.model.ViewerTheme
import kotlin.math.max
import kotlin.math.min

class PremiumPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pageBitmap: Bitmap? = null
    var pageIndex: Int = 0
    var annotationManager: AnnotationManager? = null
    var annotationRenderer: UnifiedAnnotationRenderer? = null
    var pdfCoordinateSpace: PdfCoordinateSpace = PdfCoordinateSpace()

    private var currentScale = 1.0f
    private var currentTheme = ViewerTheme.LIGHT
    private var gestureListener: PageGestureListener? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundPaint = Paint()

    interface PageGestureListener {
        fun onTap(x: Float, y: Float)
        fun onDoubleTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onScaleChanged(scale: Float)
    }

    init {
        setWillNotDraw(false)
    }

    fun setPageBitmap(bitmap: Bitmap) {
        pageBitmap = bitmap
        invalidate()
    }

    fun setTheme(theme: ViewerTheme) {
        currentTheme = theme
        invalidate()
    }

    fun setGestureListener(listener: PageGestureListener?) {
        gestureListener = listener
    }

    fun getCurrentScale(): Float = currentScale

    fun setZoom(scale: Float, animate: Boolean = false) {
        currentScale = scale
        gestureListener?.onScaleChanged(scale)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bgColor = when (currentTheme) {
            ViewerTheme.LIGHT -> Color.WHITE
            ViewerTheme.DARK -> Color.BLACK
            ViewerTheme.NIGHT -> Color.parseColor("#1a1a2e")
            ViewerTheme.SEPIA -> Color.parseColor("#f4ecd8")
            ViewerTheme.HIGH_CONTRAST -> Color.BLACK
        }
        canvas.drawColor(bgColor)

        pageBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                val src = Rect(0, 0, bitmap.width, bitmap.height)
                val dst = calculateDstRect(bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, src, dst, paint)

                annotationRenderer?.let { renderer ->
                    annotationManager?.let { manager ->
                        canvas.save()
                        canvas.clipRect(dst)
                        canvas.translate(dst.left.toFloat(), dst.top.toFloat())
                        val scaleX = dst.width().toFloat() / bitmap.width
                        val scaleY = dst.height().toFloat() / bitmap.height
                        canvas.scale(scaleX, scaleY)
                        renderer.render(canvas, manager, pageIndex, bitmap.width.toFloat(), bitmap.height.toFloat())
                        canvas.restore()
                    }
                }
            }
        }
    }

    private fun calculateDstRect(bw: Int, bh: Int): android.graphics.RectF {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bitmapAspect = bw.toFloat() / bh
        val viewAspect = viewW / viewH

        return if (bitmapAspect > viewAspect) {
            val w = viewW * currentScale
            val h = w / bitmapAspect
            val left = (viewW - w) / 2
            val top = (viewH - h) / 2
            android.graphics.RectF(left, top, left + w, top + h)
        } else {
            val h = viewH * currentScale
            val w = h * bitmapAspect
            val left = (viewW - w) / 2
            val top = (viewH - h) / 2
            android.graphics.RectF(left, top, left + w, top + h)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    companion object {
        @SuppressLint("ClickableViewAccessibility")
        fun suppressAccessibility() {}
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun View.setTouchListener() {}
