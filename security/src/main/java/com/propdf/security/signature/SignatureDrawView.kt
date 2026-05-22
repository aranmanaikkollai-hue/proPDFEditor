package com.propdf.security.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom view for capturing hand-drawn signatures with touch smoothing.
 */
class SignatureDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<PointF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var currentPath = Path()

    var onSignatureChanged: (() -> Unit)? = null

    fun getSignatureBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        draw(canvas)
        return bitmap
    }

    fun getPoints(): List<PointF> = points.toList()

    fun clear() {
        points.clear()
        path.reset()
        currentPath.reset()
        invalidate()
    }

    fun isEmpty(): Boolean = points.isEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
        canvas.drawPath(currentPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.moveTo(event.x, event.y)
                points.add(PointF(event.x, event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(event.x, event.y)
                points.add(PointF(event.x, event.y))
                invalidate()
                onSignatureChanged?.invoke()
                return true
            }
            MotionEvent.ACTION_UP -> {
                path.addPath(currentPath)
                currentPath.reset()
                onSignatureChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
