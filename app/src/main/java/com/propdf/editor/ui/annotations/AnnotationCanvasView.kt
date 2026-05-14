package com.propdf.editor.ui.annotations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class AnnotationCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }
    private val path = Path()
    private val strokes = mutableListOf<Pair<Path, Paint>>()
    private var currentTool = "freehand"
    private var currentColor = Color.RED
    private var currentWidth = 4f
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    fun setTool(tool: String) { currentTool = tool }
    fun setColor(color: Int) { currentColor = color; paint.color = color }
    fun setStrokeWidth(width: Float) { currentWidth = width; paint.strokeWidth = width }

    fun clear() {
        strokes.clear()
        path.reset()
        invalidate()
    }

    fun getStrokes(): List<Pair<Path, Paint>> = strokes.toList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT)
        strokes.forEach { (p, pt) -> canvas.drawPath(p, pt) }
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(x, y)
                lastX = x
                lastY = y
                startX = x
                startY = y
            }
            MotionEvent.ACTION_MOVE -> {
                when (currentTool) {
                    "freehand", "highlighter", "eraser" -> {
                        val dx = abs(x - lastX)
                        val dy = abs(y - lastY)
                        if (dx >= 4 || dy >= 4) {
                            path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                            lastX = x
                            lastY = y
                        }
                    }
                    "rectangle" -> {
                        path.reset()
                        path.addRect(startX, startY, x, y, Path.Direction.CW)
                    }
                    "circle" -> {
                        path.reset()
                        val rx = abs(x - startX) / 2
                        val ry = abs(y - startY) / 2
                        val cx = (startX + x) / 2
                        val cy = (startY + y) / 2
                        path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                    }
                    "arrow" -> {
                        path.reset()
                        path.moveTo(startX, startY)
                        path.lineTo(x, y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val pt = Paint(paint)
                when (currentTool) {
                    "freehand", "highlighter", "eraser" -> {
                        path.lineTo(x, y)
                        strokes.add(Path(path) to pt)
                    }
                    "rectangle" -> {
                        path.reset()
                        path.addRect(startX, startY, x, y, Path.Direction.CW)
                        strokes.add(Path(path) to pt)
                    }
                    "circle" -> {
                        path.reset()
                        val rx = abs(x - startX) / 2
                        val ry = abs(y - startY) / 2
                        val cx = (startX + x) / 2
                        val cy = (startY + y) / 2
                        path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                        strokes.add(Path(path) to pt)
                    }
                    "arrow" -> {
                        path.reset()
                        path.moveTo(startX, startY)
                        path.lineTo(x, y)
                        strokes.add(Path(path) to pt)
                    }
                }
                path.reset()
            }
        }
        invalidate()
        return true
    }
}
