package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * AnnotationCanvasView — Per-page annotation overlay.
 *
 * Key fixes vs previous version:
 * - Software layer enabled so PorterDuff.Mode.CLEAR (eraser) works correctly
 * - Per-page canvas (not full-screen overlay) — coordinates are local to the page
 * - Bitmaps are managed in a dedicated off-screen canvas to avoid redraw cost
 * - Stroke width, color, alpha all configurable per tool
 */
class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Tool names ───────────────────────────────────────────
    companion object {
        const val TOOL_NONE       = "none"
        const val TOOL_FREEHAND   = "freehand"
        const val TOOL_HIGHLIGHT  = "highlight"
        const val TOOL_RECTANGLE  = "rect"
        const val TOOL_ARROW      = "arrow"
        const val TOOL_ERASER     = "eraser"
    }

    // ── State ────────────────────────────────────────────────
    private var toolName  = TOOL_NONE
    private var toolColor = Color.BLUE
    private var strokePx  = 6f

    private val strokes   = mutableListOf<Stroke>()   // committed strokes
    private val redoStack = mutableListOf<Stroke>()    // redo buffer

    // current in-progress path
    private var activePath  : Path?  = null
    private var activeShape : Shape? = null   // for rect/arrow live preview
    private var startX = 0f
    private var startY = 0f

    // Off-screen bitmap — committed strokes are rendered here once,
    // reducing per-frame overdraw on low-end devices.
    private var cacheBitmap : Bitmap? = null
    private var cacheCanvas : Canvas? = null
    private var cacheInvalid = true          // rebuild cache next draw?

    // ── Critical: software layer for PorterDuff.CLEAR eraser ──
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    // ── Data classes ─────────────────────────────────────────
    data class Stroke(
        val path   : Path,
        val paint  : Paint,
        val tool   : String
    )

    data class Shape(
        val x1: Float, val y1: Float,
        var x2: Float, var y2: Float,
        val tool: String,
        val paint: Paint
    )

    // ── Size change → rebuild off-screen buffer ───────────────
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (w > 0 && h > 0) {
            cacheBitmap?.recycle()
            // RGB_565 saves ~50% memory vs ARGB_8888 for the annotation layer
            cacheBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                cacheCanvas = Canvas(it)
            }
            cacheInvalid = true
        }
    }

    // ── Draw ─────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        // 1. Rebuild committed-strokes cache if dirty
        if (cacheInvalid) {
            cacheCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (s in strokes) cacheCanvas?.drawPath(s.path, s.paint)
            cacheInvalid = false
        }

        // 2. Blit the committed-strokes cache
        cacheBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 3. Draw live in-progress stroke (not yet committed)
        activePath?.let { canvas.drawPath(it, buildPaint()) }

        // 4. Draw live shape preview (rect / arrow)
        activeShape?.let { drawShape(canvas, it) }
    }

    // ── Touch ─────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (toolName == TOOL_NONE) return false

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(x, y)
            MotionEvent.ACTION_MOVE -> onMove(x, y)
            MotionEvent.ACTION_UP   -> onUp(x, y)
        }
        invalidate()
        return true
    }

    private fun onDown(x: Float, y: Float) {
        startX = x; startY = y
        redoStack.clear()
        when (toolName) {
            TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                activePath = Path().apply { moveTo(x, y) }
            }
            TOOL_RECTANGLE, TOOL_ARROW -> {
                activeShape = Shape(x, y, x, y, toolName, buildPaint())
            }
        }
    }

    private fun onMove(x: Float, y: Float) {
        when (toolName) {
            TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                activePath?.quadTo(startX, startY, (x + startX) / 2f, (y + startY) / 2f)
                startX = x; startY = y
            }
            TOOL_RECTANGLE, TOOL_ARROW -> {
                activeShape?.x2 = x
                activeShape?.y2 = y
            }
        }
    }

    private fun onUp(x: Float, y: Float) {
        when (toolName) {
            TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                activePath?.lineTo(x, y)
                activePath?.let {
                    commit(Stroke(Path(it), buildPaint(), toolName))
                }
                activePath = null
            }
            TOOL_RECTANGLE, TOOL_ARROW -> {
                activeShape?.let { s ->
                    s.x2 = x; s.y2 = y
                    val p = Path()
                    buildShapePath(p, s)
                    commit(Stroke(p, buildPaint(), toolName))
                }
                activeShape = null
            }
        }
    }

    /** Commit a finished stroke to the list and mark cache dirty. */
    private fun commit(stroke: Stroke) {
        strokes.add(stroke)
        // Directly draw onto cache so we don't have to redraw everything
        cacheCanvas?.drawPath(stroke.path, stroke.paint)
        // Cache is now up-to-date
        cacheInvalid = false
    }

    // ── Paint factory ─────────────────────────────────────────
    private fun buildPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias   = true
        style         = Paint.Style.STROKE
        strokeCap     = Paint.Cap.ROUND
        strokeJoin    = Paint.Join.ROUND

        when (toolName) {
            TOOL_HIGHLIGHT -> {
                color       = toolColor
                alpha       = 90
                strokeWidth = 28f
            }
            TOOL_ERASER -> {
                // CLEAR mode requires software layer — set in init{}
                color       = Color.TRANSPARENT
                strokeWidth = 40f
                xfermode    = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECTANGLE, TOOL_ARROW -> {
                color       = toolColor
                strokeWidth = 3f
            }
            else -> { // FREEHAND
                color       = toolColor
                strokeWidth = strokePx
            }
        }
    }

    // ── Shape helpers ─────────────────────────────────────────
    private fun buildShapePath(path: Path, s: Shape) {
        when (s.tool) {
            TOOL_RECTANGLE -> path.addRect(s.x1, s.y1, s.x2, s.y2, Path.Direction.CW)
            TOOL_ARROW     -> buildArrow(path, s.x1, s.y1, s.x2, s.y2)
        }
    }

    private fun drawShape(canvas: Canvas, s: Shape) {
        val p = Path(); buildShapePath(p, s); canvas.drawPath(p, s.paint)
    }

    private fun buildArrow(path: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.moveTo(x1, y1); path.lineTo(x2, y2)
        val angle  = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val len    = 24f
        val spread = Math.PI / 6
        path.moveTo(x2, y2)
        path.lineTo(
            (x2 - len * Math.cos(angle - spread)).toFloat(),
            (y2 - len * Math.sin(angle - spread)).toFloat()
        )
        path.moveTo(x2, y2)
        path.lineTo(
            (x2 - len * Math.cos(angle + spread)).toFloat(),
            (y2 - len * Math.sin(angle + spread)).toFloat()
        )
    }

    // ── Public API ────────────────────────────────────────────

    /** Set active tool. Pass TOOL_NONE to disable touch. */
    fun setTool(tool: String, color: Int, widthPx: Float = 6f) {
        toolName  = tool
        toolColor = color
        strokePx  = widthPx
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeLast())
            cacheInvalid = true
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            commit(redoStack.removeLast())
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        cacheCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheInvalid = false
        invalidate()
    }

    fun hasAnnotations(): Boolean = strokes.isNotEmpty()

    /**
     * Export drawn strokes as a list of (path, paint) pairs,
     * with coordinates in view-space.  Caller is responsible for
     * mapping to PDF-space (divide by scaleX/scaleY).
     */
    fun getStrokes(): List<Stroke> = strokes.toList()

    /** Release off-screen bitmap — call from onRecycled / onDetach. */
    fun release() {
        cacheBitmap?.recycle()
        cacheBitmap = null
        cacheCanvas = null
    }
}
