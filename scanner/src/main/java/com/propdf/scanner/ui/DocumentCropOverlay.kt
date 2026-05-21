package com.propdf.scanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class DocumentCropOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var corners: List<PointF>? = null
    private var draggingCorner: Int = -1
    private var cornerRadius = 24f
    private var lineStroke = 4f

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#448AFF")
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(8f)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#448AFF")
        style = Paint.Style.STROKE
        strokeWidth = lineStroke
        pathEffect = CornerPathEffect(4f)
    }

    private val shadowPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }

    private val path = Path()

    var onCornersChanged: ((List<PointF>) -> Unit)? = null

    fun setCorners(newCorners: List<PointF>?) {
        corners = newCorners
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = corners ?: return
        if (c.size != 4) return

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadowPaint)

        path.reset()
        path.moveTo(c[0].x, c[0].y)
        path.lineTo(c[1].x, c[1].y)
        path.lineTo(c[2].x, c[2].y)
        path.lineTo(c[3].x, c[3].y)
        path.close()
        canvas.drawPath(path, linePaint)

        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawPath(path, clearPaint)

        c.forEachIndexed { index, point ->
            canvas.drawCircle(point.x, point.y, cornerRadius, cornerPaint)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("${index + 1}", point.x, point.y + 8f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = corners ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingCorner = findNearestCorner(event.x, event.y, c)
                return draggingCorner != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingCorner != -1) {
                    val updated = c.toMutableList()
                    updated[draggingCorner] = PointF(
                        event.x.coerceIn(0f, width.toFloat()),
                        event.y.coerceIn(0f, height.toFloat())
                    )
                    corners = updated
                    onCornersChanged?.invoke(updated)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                draggingCorner = -1
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestCorner(x: Float, y: Float, corners: List<PointF>): Int {
        var nearest = -1
        var minDist = Float.MAX_VALUE
        corners.forEachIndexed { index, point ->
            val dist = abs(point.x - x) + abs(point.y - y)
            if (dist < minDist && dist < cornerRadius * 3) {
                minDist = dist
                nearest = index
            }
        }
        return nearest
    }
}
