package com.propdfeditor.ui.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PdfSignatureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var signatureBitmap: Bitmap? = null
    private var signatureRect = RectF()
    private var isDragging = false
    private var isResizing = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var rectStartLeft = 0f
    private var rectStartTop = 0f
    private var resizeHandleSize = 40f

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1A2196F3")
        style = Paint.Style.FILL
    }

    var onSignaturePositionChanged: ((RectF) -> Unit)? = null
    var onSignaturePlaced: ((RectF) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (signatureBitmap != null && signatureRect.contains(e.x, e.y)) {
                onSignaturePlaced?.invoke(signatureRect)
                return true
            }
            return false
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (signatureBitmap == null) return false
            val scale = detector.scaleFactor
            val centerX = signatureRect.centerX()
            val centerY = signatureRect.centerY()
            val newWidth = signatureRect.width() * scale
            val newHeight = signatureRect.height() * scale

            signatureRect.set(
                centerX - newWidth / 2,
                centerY - newHeight / 2,
                centerX + newWidth / 2,
                centerY + newHeight / 2
            )
            constrainRect()
            invalidate()
            onSignaturePositionChanged?.invoke(signatureRect)
            return true
        }
    })

    fun setSignatureBitmap(bitmap: Bitmap) {
        signatureBitmap = bitmap
        val centerX = width / 2f
        val centerY = height / 2f
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetWidth = min(width * 0.4f, bitmap.width.toFloat())
        val targetHeight = targetWidth / aspectRatio

        signatureRect.set(
            centerX - targetWidth / 2,
            centerY - targetHeight / 2,
            centerX + targetWidth / 2,
            centerY + targetHeight / 2
        )
        invalidate()
    }

    fun clearSignature() {
        signatureBitmap = null
        signatureRect.setEmpty()
        invalidate()
    }

    fun getSignatureRect(): RectF = RectF(signatureRect)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        signatureBitmap?.let { bitmap ->
            // Draw semi-transparent background
            canvas.drawRect(signatureRect, backgroundPaint)

            // Draw bitmap scaled to rect
            canvas.drawBitmap(bitmap, null, signatureRect, null)

            // Draw border
            canvas.drawRect(signatureRect, borderPaint)

            // Draw resize handles at corners
            drawResizeHandle(canvas, signatureRect.left, signatureRect.top)
            drawResizeHandle(canvas, signatureRect.right, signatureRect.top)
            drawResizeHandle(canvas, signatureRect.left, signatureRect.bottom)
            drawResizeHandle(canvas, signatureRect.right, signatureRect.bottom)
        }
    }

    private fun drawResizeHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, resizeHandleSize / 2, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (signatureBitmap == null) return false

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y

                isResizing = isNearCorner(x, y)
                isDragging = !isResizing && signatureRect.contains(x, y)

                if (isDragging || isResizing) {
                    dragStartX = x
                    dragStartY = y
                    rectStartLeft = signatureRect.left
                    rectStartTop = signatureRect.top
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    signatureRect.offset(dx, dy)
                    constrainRect()
                    dragStartX = event.x
                    dragStartY = event.y
                    invalidate()
                    onSignaturePositionChanged?.invoke(signatureRect)
                } else if (isResizing) {
                    // Simple resize from bottom-right corner
                    val newRight = max(event.x, signatureRect.left + 50)
                    val newBottom = max(event.y, signatureRect.top + 50)
                    signatureRect.right = newRight
                    signatureRect.bottom = newBottom
                    invalidate()
                    onSignaturePositionChanged?.invoke(signatureRect)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isResizing = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun isNearCorner(x: Float, y: Float): Boolean {
        val corners = listOf(
            PointF(signatureRect.left, signatureRect.top),
            PointF(signatureRect.right, signatureRect.top),
            PointF(signatureRect.left, signatureRect.bottom),
            PointF(signatureRect.right, signatureRect.bottom)
        )
        return corners.any { corner ->
            val dx = x - corner.x
            val dy = y - corner.y
            (dx * dx + dy * dy) <= resizeHandleSize * resizeHandleSize
        }
    }

    private fun constrainRect() {
        val minSize = 50f
        signatureRect.left = max(0f, min(signatureRect.left, width - minSize))
        signatureRect.top = max(0f, min(signatureRect.top, height - minSize))
        signatureRect.right = max(signatureRect.left + minSize, min(signatureRect.right, width.toFloat()))
        signatureRect.bottom = max(signatureRect.top + minSize, min(signatureRect.bottom, height.toFloat()))
    }
}
