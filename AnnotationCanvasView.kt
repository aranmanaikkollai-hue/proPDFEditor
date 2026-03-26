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
        const val TOOL_NONE      = "none"
        const val TOOL_FREEHAND  = "freehand"
        const val TOOL_HIGHLIGHT = "highlight"
        const val TOOL_RECT      = "rect"
        const val TOOL_TEXT      = "text"
        const val TOOL_ERASER    = "eraser"
    }

    data class Stroke(val path: Path, val paint: Paint, val tool: String)
    data class TextAnnot(
        val x: Float, val y: Float,
        val text: String, val color: Int, val sizePx: Float
    )

    private var tool              = TOOL_NONE
    private var toolColor         = Color.BLUE
    private var currentStrokeWidth = 14f
    private var currentTextSize    = 42f
    private var pendingText: String? = null

    private val strokes    = mutableListOf<Stroke>()
    private val textAnnots = mutableListOf<TextAnnot>()
    private val undoStack  = mutableListOf<Any>()
    private val redoStack  = mutableListOf<Any>()

    private var livePath: Path? = null
    private var sx = 0f; private var sy = 0f
    private var ex = 0f; private var ey = 0f
    private var inShape = false

    private var cacheBmp   : Bitmap? = null
    private var cacheCvs   : Canvas? = null
    private var cacheDirty = true

    // Track last touch point for smooth drawing
    private var lastX = 0f
    private var lastY = 0f

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
            strokes.forEach    { cacheCvs?.drawPath(it.path, it.paint) }
            textAnnots.forEach { drawTextAnnot(cacheCvs!!, it) }
            cacheDirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        livePath?.let { canvas.drawPath(it, makeLivePaint()) }
        if (inShape) {
            val p = Path().also { buildRect(it, sx, sy, ex, ey) }
            canvas.drawPath(p, makeLivePaint())
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // CRITICAL: tell parent (ScrollView) not to steal our touch events
                requestParentNonIntercept(true)
                redoStack.clear()
                when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath = Path().apply { moveTo(x, y) }
                        lastX = x; lastY = y
                    }
                    TOOL_RECT -> {
                        sx = x; sy = y; ex = x; ey = y; inShape = true
                    }
                    TOOL_TEXT -> pendingText?.let { txt ->
                        val ta = TextAnnot(x, y, txt, toolColor, currentTextSize)
                        textAnnots.add(ta); undoStack.add(ta)
                        cacheCvs?.let { drawTextAnnot(it, ta) }
                        cacheDirty = false
                        pendingText = null
                        tool = TOOL_NONE
                        isEnabled = false
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (tool) {
                TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                    // Use quadratic bezier for smooth curves instead of raw lineTo
                    livePath?.let { path ->
                        val midX = (lastX + x) / 2f
                        val midY = (lastY + y) / 2f
                        path.quadTo(lastX, lastY, midX, midY)
                        lastX = x; lastY = y
                    }
                }
                TOOL_RECT -> { ex = x; ey = y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                requestParentNonIntercept(false)
                when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath?.lineTo(x, y)
                        livePath?.let { path ->
                            val p = makeCommittedPaint()
                            val s = Stroke(Path(path), p, tool)
                            strokes.add(s); undoStack.add(s)
                            cacheCvs?.drawPath(s.path, s.paint)
                            cacheDirty = false
                        }
                        livePath = null
                    }
                    TOOL_RECT -> {
                        val p = Path().also { buildRect(it, sx, sy, x, y) }
                        val s = Stroke(p, makeCommittedPaint(), tool)
                        strokes.add(s); undoStack.add(s)
                        cacheCvs?.drawPath(s.path, s.paint)
                        cacheDirty = false; inShape = false
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun requestParentNonIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    private fun makeLivePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            TOOL_HIGHLIGHT -> { color = toolColor; alpha = 110; strokeWidth = currentStrokeWidth }
            TOOL_ERASER    -> { color = Color.parseColor("#AAAAAA"); alpha = 80; strokeWidth = currentStrokeWidth }
            TOOL_RECT      -> { color = toolColor; strokeWidth = 3f }
            else           -> { color = toolColor; strokeWidth = currentStrokeWidth }
        }
    }

    private fun makeCommittedPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            TOOL_HIGHLIGHT -> { color = toolColor; alpha = 110; strokeWidth = currentStrokeWidth }
            TOOL_ERASER    -> {
                color = Color.TRANSPARENT; strokeWidth = currentStrokeWidth
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECT      -> { color = toolColor; strokeWidth = 3f }
            else           -> { color = toolColor; strokeWidth = currentStrokeWidth }
        }
    }

    private fun buildRect(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.addRect(x1, y1, x2, y2, Path.Direction.CW)
    }

    private fun drawTextAnnot(cvs: Canvas, t: TextAnnot) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; textSize = t.sizePx
            typeface = Typeface.DEFAULT_BOLD
        }
        val bounds = Rect()
        tp.getTextBounds(t.text, 0, t.text.length, bounds)
        val pad = 8f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 200); style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val l = t.x - pad; val top = t.y - bounds.height() - pad
        val r = t.x + bounds.width() + pad; val bot = t.y + pad
        cvs.drawRoundRect(l, top, r, bot, 8f, 8f, bgPaint)
        cvs.drawRoundRect(l, top, r, bot, 8f, 8f, borderPaint)
        cvs.drawText(t.text, t.x, t.y, tp)
    }

    // ---- Public API --------------------------------------------------

    fun setTool(t: String, color: Int) {
        tool = t; toolColor = color
        isEnabled = (t != TOOL_NONE)
    }

    fun setStrokeWidth(w: Float)  { currentStrokeWidth = w.coerceAtLeast(2f) }
    fun setTextSize(sz: Float)    { currentTextSize    = sz.coerceAtLeast(12f) }
    fun setColor(c: Int)          { toolColor          = c }
    fun setPendingText(t: String) { pendingText = t; isEnabled = true }

    fun undo() {
        if (undoStack.isEmpty()) return
        val last = undoStack.removeLast(); redoStack.add(last)
        when (last) {
            is Stroke    -> strokes.remove(last)
            is TextAnnot -> textAnnots.remove(last)
        }
        cacheDirty = true; invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast(); undoStack.add(next)
        when (next) {
            is Stroke    -> { strokes.add(next); cacheCvs?.drawPath(next.path, next.paint); cacheDirty = false }
            is TextAnnot -> { textAnnots.add(next); cacheCvs?.let { drawTextAnnot(it, next) }; cacheDirty = false }
        }
        invalidate()
    }

    fun clearAll() {
        strokes.clear(); textAnnots.clear()
        undoStack.clear(); redoStack.clear()
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheDirty = false; invalidate()
    }

    fun canUndo()        = undoStack.isNotEmpty()
    fun canRedo()        = redoStack.isNotEmpty()
    fun hasAnnotations() = strokes.isNotEmpty() || textAnnots.isNotEmpty()
    fun getStrokes()     = strokes.toList()
    fun getTextAnnots()  = textAnnots.toList()
    fun release()        { cacheBmp?.recycle(); cacheBmp = null; cacheCvs = null }
}
