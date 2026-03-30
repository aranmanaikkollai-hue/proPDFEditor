package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent

class AnnotationCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val TOOL_NONE       = "none"
        const val TOOL_FREEHAND   = "freehand"
        const val TOOL_HIGHLIGHT  = "highlight"
        const val TOOL_RECT       = "rect"
        const val TOOL_TEXT       = "text"
        const val TOOL_ERASER     = "eraser"
        const val TOOL_MOVE_TEXT  = "move_text"
        const val TOOL_UNDERLINE  = "underline"
        const val TOOL_STRIKEOUT  = "strikeout"
        const val TOOL_ARROW      = "arrow"
        const val TOOL_CIRCLE     = "circle"
        const val TOOL_STAMP      = "stamp"

        // Standard highlighter yellow - matches physical yellow highlighter
        const val HIGHLIGHT_DEFAULT_COLOR = 0xFFFFFF00.toInt()
    }

    data class Stroke(val path: Path, val paint: Paint, val tool: String, val stampText: String = "")

    data class TextAnnot(
        var x: Float, var y: Float,
        val text: String, val color: Int, val sizePx: Float
    )

    private var tool               = TOOL_NONE
    private var toolColor          = Color.parseColor("#E53935")
    private var currentStrokeWidth = 14f
    private var currentTextSize    = 44f
    private var pendingText: String? = null
    private var pendingStamp: String? = null

    private val strokes    = mutableListOf<Stroke>()
    private val textAnnots = mutableListOf<TextAnnot>()
    private val undoStack  = mutableListOf<Any>()
    private val redoStack  = mutableListOf<Any>()

    // Drawing state
    private var livePath: Path? = null
    private var lastX = 0f; private var lastY = 0f
    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var inShape = false

    // Drag state for text
    private var draggedAnnot: TextAnnot? = null
    private var dragOffX = 0f; private var dragOffY = 0f

    // Off-screen cache
    private var cacheBmp   : Bitmap? = null
    private var cacheCvs   : Canvas? = null
    private var cacheDirty = true

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            cacheBmp?.recycle()
            cacheBmp   = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCvs   = Canvas(cacheBmp!!)
            cacheDirty = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cacheDirty) {
            cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokes.forEach    { s -> cacheCvs?.let { drawStroke(it, s) } }
            textAnnots.forEach { t -> drawTextAnnot(cacheCvs!!, t, t == draggedAnnot) }
            cacheDirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // Live preview
        livePath?.let { canvas.drawPath(it, makeLivePaint()) }
        if (inShape) drawShapePreview(canvas, startX, startY, endX, endY)
    }

    private fun drawStroke(cvs: Canvas, s: Stroke) {
        when (s.tool) {
            TOOL_STAMP -> {
                // Draw stamp text from path bounds
                val bounds = RectF()
                s.path.computeBounds(bounds, true)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.paint.color; textSize = 28f
                    typeface = Typeface.DEFAULT_BOLD
                }
                cvs.drawText(s.stampText.ifEmpty { "STAMP" },
                    bounds.left, bounds.bottom, tp)
            }
            else -> cvs.drawPath(s.path, s.paint)
        }
    }

    private fun drawShapePreview(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = makeLivePaint()
        when (tool) {
            TOOL_RECT   -> {
                val path = Path().apply { addRect(x1, y1, x2, y2, Path.Direction.CW) }
                canvas.drawPath(path, p)
            }
            TOOL_CIRCLE -> canvas.drawOval(
                minOf(x1,x2), minOf(y1,y2), maxOf(x1,x2), maxOf(y1,y2), p)
            TOOL_ARROW  -> drawArrow(canvas, x1, y1, x2, y2, p)
            TOOL_UNDERLINE, TOOL_STRIKEOUT -> canvas.drawLine(x1, y1, x2, y1, p)
        }
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, p: Paint) {
        canvas.drawLine(x1, y1, x2, y2, p)
        val angle  = Math.atan2((y2-y1).toDouble(), (x2-x1).toDouble())
        val aLen   = 24f
        val wing   = Math.PI / 6
        val ax1    = (x2 - aLen * Math.cos(angle - wing)).toFloat()
        val ay1    = (y2 - aLen * Math.sin(angle - wing)).toFloat()
        val ax2    = (x2 - aLen * Math.cos(angle + wing)).toFloat()
        val ay2    = (y2 - aLen * Math.sin(angle + wing)).toFloat()
        canvas.drawLine(x2, y2, ax1, ay1, p)
        canvas.drawLine(x2, y2, ax2, ay2, p)
    }

    private fun drawTextAnnot(cvs: Canvas, t: TextAnnot, selected: Boolean) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; textSize = t.sizePx
            typeface = Typeface.DEFAULT; isLinearText = true; isAntiAlias = true
        }
        if (selected) {
            val bounds = Rect(); tp.getTextBounds(t.text, 0, t.text.length, bounds)
            cvs.drawRoundRect(
                t.x - 4f, t.y - bounds.height() - 4f,
                t.x + bounds.width() + 4f, t.y + 4f,
                4f, 4f, Paint().apply { color = Color.argb(50, 33, 150, 243); style = Paint.Style.FILL }
            )
        }
        cvs.drawText(t.text, t.x, t.y, tp)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestParentNonIntercept(true); redoStack.clear()
                when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath = Path().apply { moveTo(x, y) }
                        lastX = x; lastY = y
                    }
                    TOOL_RECT, TOOL_CIRCLE, TOOL_ARROW, TOOL_UNDERLINE, TOOL_STRIKEOUT -> {
                        startX = x; startY = y; endX = x; endY = y; inShape = true
                    }
                    TOOL_TEXT -> pendingText?.let { txt ->
                        val ta = TextAnnot(x, y - currentTextSize * 0.3f, txt, toolColor, currentTextSize)
                        textAnnots.add(ta); undoStack.add(ta)
                        cacheCvs?.let { drawTextAnnot(it, ta, false) }
                        cacheDirty = false
                        pendingText = null; tool = TOOL_NONE; isEnabled = false
                    }
                    TOOL_STAMP -> pendingStamp?.let { stamp ->
                        val p = Path().apply { moveTo(x, y) }
                        val paint = makeCommittedPaint().apply { color = toolColor }
                        val s = Stroke(p, paint, TOOL_STAMP, stamp)
                        strokes.add(s); undoStack.add(s)
                        drawStroke(cacheCvs!!, s); cacheDirty = false
                        pendingStamp = null; tool = TOOL_NONE; isEnabled = false
                    }
                    TOOL_MOVE_TEXT -> {
                        draggedAnnot = findAnnotAt(x, y)
                        draggedAnnot?.let { a -> dragOffX = x - a.x; dragOffY = y - a.y }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (tool) {
                TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                    livePath?.let {
                        val mx = (lastX + x) / 2f; val my = (lastY + y) / 2f
                        it.quadTo(lastX, lastY, mx, my); lastX = x; lastY = y
                    }
                }
                TOOL_RECT, TOOL_CIRCLE, TOOL_ARROW -> { endX = x; endY = y }
                TOOL_UNDERLINE, TOOL_STRIKEOUT -> { endX = x; endY = startY } // horizontal only
                TOOL_MOVE_TEXT -> draggedAnnot?.let { a ->
                    a.x = x - dragOffX; a.y = y - dragOffY; cacheDirty = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestParentNonIntercept(false)
                when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath?.lineTo(x, y)
                        livePath?.let { path ->
                            val s = Stroke(Path(path), makeCommittedPaint(), tool)
                            strokes.add(s); undoStack.add(s)
                            cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false
                        }
                        livePath = null
                    }
                    TOOL_RECT -> commitShape { Path().apply { addRect(startX, startY, x, y, Path.Direction.CW) } }
                    TOOL_CIRCLE -> commitShape {
                        Path().apply { addOval(minOf(startX,x), minOf(startY,y), maxOf(startX,x), maxOf(y,startY), Path.Direction.CW) }
                    }
                    TOOL_ARROW -> commitShape {
                        Path().apply {
                            moveTo(startX, startY); lineTo(x, y)
                            val angle = Math.atan2((y-startY).toDouble(), (x-startX).toDouble())
                            val aLen = 24f; val wing = Math.PI / 6
                            lineTo((x - aLen*Math.cos(angle-wing)).toFloat(), (y - aLen*Math.sin(angle-wing)).toFloat())
                            moveTo(x, y)
                            lineTo((x - aLen*Math.cos(angle+wing)).toFloat(), (y - aLen*Math.sin(angle+wing)).toFloat())
                        }
                    }
                    TOOL_UNDERLINE -> commitShape {
                        Path().apply { moveTo(startX, y + 4f); lineTo(x, y + 4f) }
                    }
                    TOOL_STRIKEOUT -> commitShape {
                        Path().apply { moveTo(startX, startY); lineTo(x, startY) }
                    }
                    TOOL_MOVE_TEXT -> draggedAnnot = null
                }
                inShape = false
            }
        }
        invalidate(); return true
    }

    private fun commitShape(buildPath: () -> Path) {
        val s = Stroke(buildPath(), makeCommittedPaint(), tool)
        strokes.add(s); undoStack.add(s)
        cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false
    }

    private fun findAnnotAt(x: Float, y: Float): TextAnnot? =
        textAnnots.lastOrNull { t ->
            val tp = Paint().apply { textSize = t.sizePx }
            val b  = Rect(); tp.getTextBounds(t.text, 0, t.text.length, b)
            x >= t.x - 10 && x <= t.x + b.width() + 10 &&
            y >= t.y - b.height() - 10 && y <= t.y + 10
        }

    private fun makeLivePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            // FIX: Highlight default is neon yellow #FFFF00 at 50% alpha
            TOOL_HIGHLIGHT  -> { color = toolColor; alpha = 128; strokeWidth = currentStrokeWidth }
            TOOL_ERASER     -> { color = Color.parseColor("#AAAAAA"); alpha = 80; strokeWidth = currentStrokeWidth }
            TOOL_UNDERLINE  -> { color = toolColor; strokeWidth = 3f; pathEffect = null }
            TOOL_STRIKEOUT  -> { color = toolColor; strokeWidth = 2f; pathEffect = null }
            TOOL_ARROW      -> { color = toolColor; strokeWidth = 2.5f }
            TOOL_CIRCLE     -> { color = toolColor; strokeWidth = 2.5f }
            TOOL_RECT       -> { color = toolColor; strokeWidth = 2.5f }
            else            -> { color = toolColor; strokeWidth = currentStrokeWidth; alpha = 255 }
        }
    }

    private fun makeCommittedPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            // FIX: Highlight stored with correct 50% alpha on the actual chosen color
            TOOL_HIGHLIGHT  -> { color = toolColor; alpha = 128; strokeWidth = currentStrokeWidth }
            TOOL_ERASER     -> {
                color = Color.TRANSPARENT; strokeWidth = currentStrokeWidth
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_UNDERLINE  -> { color = toolColor; strokeWidth = 3f; alpha = 255 }
            TOOL_STRIKEOUT  -> { color = toolColor; strokeWidth = 2f; alpha = 255 }
            TOOL_ARROW      -> { color = toolColor; strokeWidth = 2.5f; alpha = 255 }
            TOOL_CIRCLE     -> { color = toolColor; strokeWidth = 2.5f; alpha = 255 }
            TOOL_RECT       -> { color = toolColor; strokeWidth = 2.5f; alpha = 255 }
            TOOL_STAMP      -> { color = toolColor; alpha = 255; textSize = 28f; style = Paint.Style.FILL }
            else            -> { color = toolColor; strokeWidth = currentStrokeWidth; alpha = 255 }
        }
    }

    private fun requestParentNonIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) { p.requestDisallowInterceptTouchEvent(disallow); p = p.parent }
    }

    // Public API
    fun setTool(t: String, color: Int) {
        tool = t; toolColor = color; isEnabled = (t != TOOL_NONE)
    }
    fun setStrokeWidth(w: Float)   { currentStrokeWidth = w.coerceAtLeast(2f) }
    fun setTextSize(sz: Float)     { currentTextSize    = sz.coerceAtLeast(12f) }
    fun setColor(c: Int)           { toolColor = c }
    fun setPendingText(t: String)  { pendingText = t; isEnabled = true }
    fun setPendingStamp(s: String) { pendingStamp = s; isEnabled = true }

    fun undo() {
        if (undoStack.isEmpty()) return
        val last = undoStack.removeLast(); redoStack.add(last)
        when (last) { is Stroke -> strokes.remove(last); is TextAnnot -> textAnnots.remove(last) }
        cacheDirty = true; invalidate()
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast(); undoStack.add(next)
        when (next) {
            is Stroke    -> { strokes.add(next); cacheCvs?.drawPath(next.path, next.paint); cacheDirty = false }
            is TextAnnot -> { textAnnots.add(next); cacheCvs?.let { drawTextAnnot(it, next, false) }; cacheDirty = false }
        }
        invalidate()
    }
    fun clearAll() {
        strokes.clear(); textAnnots.clear(); undoStack.clear(); redoStack.clear()
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheDirty = false; invalidate()
    }
    fun hasAnnotations() = strokes.isNotEmpty() || textAnnots.isNotEmpty()
    fun getStrokes()     = strokes.toList()
    fun getTextAnnots()  = textAnnots.toList()
    fun release()        { cacheBmp?.recycle(); cacheBmp = null; cacheCvs = null }
}
