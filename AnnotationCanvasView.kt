package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class AnnotationCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val TOOL_NONE      = "none"
        const val TOOL_FREEHAND  = "freehand"
        const val TOOL_HIGHLIGHT = "highlight"
        const val TOOL_RECTANGLE = "rect"
        const val TOOL_ARROW     = "arrow"
        const val TOOL_ERASER    = "eraser"
    }

    data class Stroke(val path: Path, val paint: Paint, val tool: String)

    private var tool      = TOOL_NONE
    private var color     = Color.BLUE
    private var width     = 6f

    private val strokes   = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<Stroke>()
    private var livePath  : Path?   = null
    private var shapeX1   = 0f; private var shapeY1 = 0f
    private var shapeX2   = 0f; private var shapeY2 = 0f
    private var inShape   = false

    // Off-screen cache of committed strokes
    private var cacheBmp : Bitmap? = null
    private var cacheCvs : Canvas? = null
    private var dirty    = true

    // ⚠️ MUST be SOFTWARE for PorterDuff.CLEAR (eraser) to work
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            cacheBmp?.recycle()
            cacheBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCvs = Canvas(cacheBmp!!)
            dirty = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (dirty) {
            cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokes.forEach { cacheCvs?.drawPath(it.path, it.paint) }
            dirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        livePath?.let { canvas.drawPath(it, makePaint()) }
        if (inShape) {
            val p = Path()
            buildShape(p, shapeX1, shapeY1, shapeX2, shapeY2)
            canvas.drawPath(p, makePaint())
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y
        try {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    undoStack.clear()
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER ->
                            livePath = Path().apply { moveTo(x, y) }
                        TOOL_RECTANGLE, TOOL_ARROW -> {
                            shapeX1 = x; shapeY1 = y; shapeX2 = x; shapeY2 = y; inShape = true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> livePath?.lineTo(x, y)
                        TOOL_RECTANGLE, TOOL_ARROW -> { shapeX2 = x; shapeY2 = y }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                            livePath?.lineTo(x, y)
                            livePath?.let {
                                val s = Stroke(Path(it), makePaint(), tool)
                                strokes.add(s)
                                cacheCvs?.drawPath(s.path, s.paint)
                                dirty = false
                            }
                            livePath = null
                        }
                        TOOL_RECTANGLE, TOOL_ARROW -> {
                            shapeX2 = x; shapeY2 = y
                            val p = Path(); buildShape(p, shapeX1, shapeY1, x, y)
                            val s = Stroke(p, makePaint(), tool)
                            strokes.add(s)
                            cacheCvs?.drawPath(s.path, s.paint)
                            dirty = false; inShape = false
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        invalidate()
        return true
    }

    private fun makePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        when (tool) {
            TOOL_HIGHLIGHT -> { color = this@AnnotationCanvasView.color; alpha = 80; strokeWidth = 28f }
            TOOL_ERASER    -> { color = Color.TRANSPARENT; strokeWidth = 44f
                               xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            TOOL_RECTANGLE, TOOL_ARROW -> { color = this@AnnotationCanvasView.color; strokeWidth = 3f }
            else -> { color = this@AnnotationCanvasView.color; strokeWidth = width }
        }
    }

    private fun buildShape(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        when (tool) {
            TOOL_RECTANGLE -> p.addRect(x1, y1, x2, y2, Path.Direction.CW)
            TOOL_ARROW -> {
                p.moveTo(x1, y1); p.lineTo(x2, y2)
                val a = Math.atan2((y2-y1).toDouble(), (x2-x1).toDouble())
                val L = 24f; val s = Math.PI / 6
                p.moveTo(x2, y2)
                p.lineTo((x2-L*Math.cos(a-s)).toFloat(), (y2-L*Math.sin(a-s)).toFloat())
                p.moveTo(x2, y2)
                p.lineTo((x2-L*Math.cos(a+s)).toFloat(), (y2-L*Math.sin(a+s)).toFloat())
            }
        }
    }

    fun setTool(t: String, c: Int, w: Float = 6f) { tool = t; color = c; width = w; isEnabled = t != TOOL_NONE }
    fun undo() { if (strokes.isNotEmpty()) { undoStack.add(strokes.removeLast()); dirty = true; invalidate() } }
    fun clearAll() { strokes.clear(); undoStack.clear(); cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR); dirty = false; invalidate() }
    fun hasAnnotations() = strokes.isNotEmpty()
    fun getStrokes(): List<Stroke> = strokes.toList()
    fun release() { cacheBmp?.recycle(); cacheBmp = null; cacheCvs = null }
}
