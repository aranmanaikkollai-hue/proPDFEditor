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
        const val TOOL_RECT      = "rect"
        const val TOOL_TEXT      = "text"
        const val TOOL_ERASER    = "eraser"
    }

    data class Stroke(val path: Path, val paint: Paint, val tool: String)
    data class TextAnnot(val x: Float, val y: Float, val text: String,
                         val color: Int, val sizePx: Float)

    // ── Tool state ─────────────────────────────────────────────
    private var tool         = TOOL_NONE
    private var toolColor    = Color.BLUE
    // FIX: store width/size as their own vars — set BEFORE building paint
    private var currentStrokeWidth = 14f
    private var currentTextSize    = 42f
    private var pendingText: String? = null

    // ── Annotation lists ───────────────────────────────────────
    private val strokes    = mutableListOf<Stroke>()
    private val textAnnots = mutableListOf<TextAnnot>()
    private val undoStack  = mutableListOf<Any>()
    private val redoStack  = mutableListOf<Any>()

    // ── Live drawing ───────────────────────────────────────────
    private var livePath: Path? = null
    private var sx = 0f; private var sy = 0f
    private var ex = 0f; private var ey = 0f
    private var inShape = false

    // ── Off-screen cache ───────────────────────────────────────
    // FIX: use a separate ARGB_8888 bitmap with LAYER_TYPE_SOFTWARE
    // so PorterDuff.CLEAR actually erases instead of drawing black
    private var cacheBmp   : Bitmap? = null
    private var cacheCvs   : Canvas? = null
    private var cacheDirty = true

    init {
        // REQUIRED: without software layer, PorterDuff.CLEAR draws BLACK not transparent
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            cacheBmp?.recycle()
            // ARGB_8888 required — RGB_565 has no alpha channel so eraser can't work
            cacheBmp   = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCvs   = Canvas(cacheBmp!!)
            cacheDirty = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cacheDirty) {
            cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokes.forEach    { s -> cacheCvs?.drawPath(s.path, s.paint) }
            textAnnots.forEach { t -> drawTextAnnot(cacheCvs!!, t) }
            cacheDirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // Live preview of current stroke
        livePath?.let { canvas.drawPath(it, makeLivePaint()) }
        if (inShape) {
            val p = Path().also { addRect(it, sx, sy, ex, ey) }
            canvas.drawPath(p, makeLivePaint())
        }
    }

    // ── Touch ──────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y
        try {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    redoStack.clear()
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER ->
                            livePath = Path().apply { moveTo(x, y) }
                        TOOL_RECT ->
                            { sx = x; sy = y; ex = x; ey = y; inShape = true }
                        TOOL_TEXT -> pendingText?.let { txt ->
                            val ta = TextAnnot(x, y, txt, toolColor, currentTextSize)
                            textAnnots.add(ta); undoStack.add(ta)
                            // Draw onto cache immediately
                            cacheCvs?.let { drawTextAnnot(it, ta) }
                            cacheDirty = false
                            pendingText = null; tool = TOOL_NONE
                            isEnabled = false   // disable until next tool selected
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER ->
                        livePath?.lineTo(x, y)
                    TOOL_RECT -> { ex = x; ey = y }
                }
                MotionEvent.ACTION_UP -> when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath?.lineTo(x, y)
                        livePath?.let { path ->
                            // FIX: snapshot paint AT commit time with current width/color
                            val committedPaint = makeCommittedPaint()
                            val s = Stroke(Path(path), committedPaint, tool)
                            strokes.add(s); undoStack.add(s)
                            // Draw to cache — eraser uses CLEAR mode on cache canvas
                            cacheCvs?.drawPath(s.path, s.paint)
                            cacheDirty = false
                        }
                        livePath = null
                    }
                    TOOL_RECT -> {
                        val p = Path().also { addRect(it, sx, sy, x, y) }
                        val s = Stroke(p, makeCommittedPaint(), tool)
                        strokes.add(s); undoStack.add(s)
                        cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false
                        inShape = false
                    }
                }
            }
        } catch (_: Exception) {}
        invalidate()
        return true
    }

    // ── Paint builders ─────────────────────────────────────────

    /**
     * Paint for the live (in-progress) stroke preview.
     * Uses current tool state.
     */
    private fun makeLivePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            TOOL_HIGHLIGHT -> {
                color = toolColor; alpha = 110
                strokeWidth = currentStrokeWidth
            }
            TOOL_ERASER -> {
                // Live preview shows a semi-transparent circle to guide user
                color = Color.parseColor("#AAAAAA"); alpha = 80
                strokeWidth = currentStrokeWidth
                style = Paint.Style.STROKE
            }
            TOOL_RECT -> {
                color = toolColor; strokeWidth = 3f
            }
            else -> {  // FREEHAND
                color = toolColor; strokeWidth = currentStrokeWidth
            }
        }
    }

    /**
     * Paint for committing a finished stroke to the cache.
     * Eraser uses CLEAR mode so it truly erases pixels.
     */
    private fun makeCommittedPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        when (tool) {
            TOOL_HIGHLIGHT -> {
                color = toolColor; alpha = 110
                strokeWidth = currentStrokeWidth
            }
            TOOL_ERASER -> {
                // FIX: CLEAR mode + software layer = actual erasing
                color = Color.TRANSPARENT
                strokeWidth = currentStrokeWidth
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECT -> {
                color = toolColor; strokeWidth = 3f
            }
            else -> {  // FREEHAND
                color = toolColor; strokeWidth = currentStrokeWidth
            }
        }
    }

    private fun addRect(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.addRect(x1, y1, x2, y2, Path.Direction.CW)
    }

    private fun drawTextAnnot(cvs: Canvas, t: TextAnnot) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; textSize = t.sizePx; typeface = Typeface.DEFAULT_BOLD
        }
        val bounds = Rect()
        tp.getTextBounds(t.text, 0, t.text.length, bounds)
        val pad = 8f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 200); style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        val left   = t.x - pad
        val top    = t.y - bounds.height() - pad
        val right  = t.x + bounds.width() + pad
        val bottom = t.y + pad
        cvs.drawRoundRect(left, top, right, bottom, 8f, 8f, bg)
        cvs.drawRoundRect(left, top, right, bottom, 8f, 8f, border)
        cvs.drawText(t.text, t.x, t.y, tp)
    }

    // ── Public API ─────────────────────────────────────────────

    /** Set active tool and color. Width is set separately via setStrokeWidth(). */
    fun setTool(t: String, color: Int) {
        tool = t; toolColor = color
        isEnabled = (t != TOOL_NONE)
    }

    /** FIX: updating width immediately applies to next stroke */
    fun setStrokeWidth(w: Float) { currentStrokeWidth = w.coerceAtLeast(2f) }

    /** FIX: updating text size immediately applies to next text annotation */
    fun setTextSize(sz: Float)   { currentTextSize = sz.coerceAtLeast(12f) }

    fun setColor(c: Int)          { toolColor = c }
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
        strokes.clear(); textAnnots.clear(); undoStack.clear(); redoStack.clear()
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
