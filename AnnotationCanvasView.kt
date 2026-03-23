package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * AnnotationCanvasView — Per-page annotation overlay.
 *
 * Fixes:
 *  - setLayerType(LAYER_TYPE_SOFTWARE) in init — eraser (PorterDuff.CLEAR) works correctly
 *  - Off-screen committed-stroke cache — fast redraws on low-end devices
 *  - No crash on touch — all path operations guarded
 *  - Undo stack with redo support
 *  - String-based tool names — no cross-file enum dependency
 */
class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Tool constants ────────────────────────────────────────
    companion object {
        const val TOOL_NONE      = "none"
        const val TOOL_FREEHAND  = "freehand"
        const val TOOL_HIGHLIGHT = "highlight"
        const val TOOL_RECTANGLE = "rect"
        const val TOOL_ARROW     = "arrow"
        const val TOOL_ERASER    = "eraser"
    }

    // ── State ────────────────────────────────────────────────
    private var toolName  = TOOL_NONE
    private var toolColor = Color.BLUE
    private var strokePx  = 6f

    data class Stroke(val path: Path, val paint: Paint, val tool: String)

    private val strokes   = mutableListOf<Stroke>()
    private val undoStack = mutableListOf<Stroke>()

    private var activePath  : Path?  = null
    private var shapeStart  : PointF? = null
    private var shapeEnd    : PointF? = null

    // Off-screen bitmap for committed strokes
    private var cacheBmp : Bitmap? = null
    private var cacheCvs : Canvas? = null
    private var dirty    = true

    // CRITICAL: Software layer required for PorterDuff.CLEAR (eraser)
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    // ── Resize ────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (w > 0 && h > 0) {
            cacheBmp?.recycle()
            cacheBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCvs = Canvas(cacheBmp!!)
            dirty = true
        }
    }

    // ── Draw ─────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (dirty) {
            cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokes.forEach { s -> cacheCvs?.drawPath(s.path, s.paint) }
            dirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        activePath?.let { canvas.drawPath(it, buildPaint()) }

        // Live shape preview
        if (toolName == TOOL_RECTANGLE || toolName == TOOL_ARROW) {
            val s = shapeStart; val e = shapeEnd
            if (s != null && e != null) {
                val p = Path()
                buildShapePath(p, s.x, s.y, e.x, e.y)
                canvas.drawPath(p, buildPaint())
            }
        }
    }

    // ── Touch ─────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (toolName == TOOL_NONE) return false
        val x = event.x
        val y = event.y
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    undoStack.clear()
                    when (toolName) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                            activePath = Path().apply { moveTo(x, y) }
                        }
                        TOOL_RECTANGLE, TOOL_ARROW -> {
                            shapeStart = PointF(x, y)
                            shapeEnd   = PointF(x, y)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    when (toolName) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                            activePath?.lineTo(x, y)
                        }
                        TOOL_RECTANGLE, TOOL_ARROW -> {
                            shapeEnd = PointF(x, y)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when (toolName) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                            activePath?.lineTo(x, y)
                            activePath?.let {
                                val committed = Stroke(Path(it), buildPaint(), toolName)
                                strokes.add(committed)
                                cacheCvs?.drawPath(committed.path, committed.paint)
                                // Cache now up-to-date
                                dirty = false
                            }
                            activePath = null
                        }
                        TOOL_RECTANGLE, TOOL_ARROW -> {
                            val s = shapeStart; val e = shapeEnd
                            if (s != null && e != null) {
                                val p = Path()
                                buildShapePath(p, s.x, s.y, e.x, e.y)
                                val committed = Stroke(p, buildPaint(), toolName)
                                strokes.add(committed)
                                cacheCvs?.drawPath(committed.path, committed.paint)
                                dirty = false
                            }
                            shapeStart = null; shapeEnd = null
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Never crash on draw
        }
        invalidate()
        return true
    }

    // ── Paint factory ─────────────────────────────────────────

    private fun buildPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        style       = Paint.Style.STROKE
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
        when (toolName) {
            TOOL_HIGHLIGHT -> {
                color       = toolColor
                alpha       = 90
                strokeWidth = 28f
            }
            TOOL_ERASER -> {
                color       = Color.TRANSPARENT
                strokeWidth = 44f
                xfermode    = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECTANGLE, TOOL_ARROW -> {
                color       = toolColor
                strokeWidth = 3f
            }
            else -> {
                color       = toolColor
                strokeWidth = strokePx
            }
        }
    }

    // ── Shape helpers ─────────────────────────────────────────

    private fun buildShapePath(path: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        when (toolName) {
            TOOL_RECTANGLE -> path.addRect(x1, y1, x2, y2, Path.Direction.CW)
            TOOL_ARROW     -> {
                path.moveTo(x1, y1); path.lineTo(x2, y2)
                val a = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                val L = 24f; val sp = Math.PI / 6
                path.moveTo(x2, y2)
                path.lineTo((x2 - L * Math.cos(a - sp)).toFloat(), (y2 - L * Math.sin(a - sp)).toFloat())
                path.moveTo(x2, y2)
                path.lineTo((x2 - L * Math.cos(a + sp)).toFloat(), (y2 - L * Math.sin(a + sp)).toFloat())
            }
        }
    }

    // ── Public API ────────────────────────────────────────────

    fun setTool(tool: String, color: Int, widthPx: Float = 6f) {
        toolName  = tool
        toolColor = color
        strokePx  = widthPx
        isEnabled = tool != TOOL_NONE
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undoStack.add(strokes.removeLast())
            dirty = true
            invalidate()
        }
    }

    fun redo() {
        if (undoStack.isNotEmpty()) {
            val s = undoStack.removeLast()
            strokes.add(s)
            cacheCvs?.drawPath(s.path, s.paint)
            dirty = false
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear(); undoStack.clear()
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        dirty = false
        invalidate()
    }

    fun hasAnnotations() = strokes.isNotEmpty()
    fun getStrokes(): List<Stroke> = strokes.toList()

    fun release() {
        cacheBmp?.recycle()
        cacheBmp = null
        cacheCvs = null
    }
}
