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

    // ── Tool state ────────────────────────────────────────────
    var tool        = TOOL_NONE;  private set
    var toolColor   = Color.BLUE; private set
    var strokeWidth = 8f;         private set  // freehand / highlight / eraser
    var textSizePx  = 42f;        private set
    private var pendingText: String? = null

    // ── Annotation lists ──────────────────────────────────────
    private val strokes    = mutableListOf<Stroke>()
    private val textAnnots = mutableListOf<TextAnnot>()

    // Undo / redo stacks — each entry is either a Stroke or TextAnnot
    private val undoStack  = mutableListOf<Any>()  // Stroke | TextAnnot
    private val redoStack  = mutableListOf<Any>()  // Stroke | TextAnnot

    // ── Live drawing ──────────────────────────────────────────
    private var livePath : Path?  = null
    private var sx = 0f; private var sy = 0f   // shape start
    private var ex = 0f; private var ey = 0f   // shape end
    private var inShape = false

    // ── Off-screen cache ──────────────────────────────────────
    private var cacheBmp   : Bitmap? = null
    private var cacheCvs   : Canvas? = null
    private var cacheDirty = true

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // required for eraser
        setWillNotDraw(false)
    }

    // ── Size changed ──────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            cacheBmp?.recycle()
            cacheBmp   = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cacheCvs   = Canvas(cacheBmp!!)
            cacheDirty = true
        }
    }

    // ── Draw ─────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        if (cacheDirty) rebuildCache()
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        livePath?.let { canvas.drawPath(it, buildPaint()) }
        if (inShape) {
            val p = Path().also { buildRect(it, sx, sy, ex, ey) }
            canvas.drawPath(p, buildPaint())
        }
    }

    private fun rebuildCache() {
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        strokes.forEach    { cacheCvs?.drawPath(it.path, it.paint) }
        textAnnots.forEach { drawTextAnnot(cacheCvs!!, it) }
        cacheDirty = false
    }

    private fun drawTextAnnot(cvs: Canvas, t: TextAnnot) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = t.color; textSize = t.sizePx
            typeface = Typeface.DEFAULT_BOLD
        }
        val bounds = Rect()
        tp.getTextBounds(t.text, 0, t.text.length, bounds)
        val pad = 6f
        // White background box
        cvs.drawRoundRect(
            t.x - pad, t.y - bounds.height() - pad,
            t.x + bounds.width() + pad, t.y + pad,
            8f, 8f,
            Paint().apply { color = Color.argb(190,255,255,255); style = Paint.Style.FILL }
        )
        // Border
        cvs.drawRoundRect(
            t.x - pad, t.y - bounds.height() - pad,
            t.x + bounds.width() + pad, t.y + pad,
            8f, 8f,
            Paint().apply { color = t.color; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        )
        cvs.drawText(t.text, t.x, t.y, tp)
    }

    // ── Touch ─────────────────────────────────────────────────
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
                        TOOL_RECT -> { sx = x; sy = y; ex = x; ey = y; inShape = true }
                        TOOL_TEXT -> pendingText?.let { txt ->
                            val ta = TextAnnot(x, y, txt, toolColor, textSizePx)
                            textAnnots.add(ta)
                            undoStack.add(ta)
                            drawTextAnnot(cacheCvs!!, ta)
                            cacheDirty = false
                            pendingText = null; tool = TOOL_NONE; invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> livePath?.lineTo(x, y)
                    TOOL_RECT -> { ex = x; ey = y }
                }
                MotionEvent.ACTION_UP -> when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath?.lineTo(x, y)
                        livePath?.let {
                            val s = Stroke(Path(it), buildPaint(), tool)
                            strokes.add(s); undoStack.add(s)
                            cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false
                        }
                        livePath = null
                    }
                    TOOL_RECT -> {
                        ex = x; ey = y
                        val p = Path().also { buildRect(it, sx, sy, x, y) }
                        val s = Stroke(p, buildPaint(), tool)
                        strokes.add(s); undoStack.add(s)
                        cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false
                        inShape = false
                    }
                }
            }
        } catch (_: Exception) {}
        invalidate(); return true
    }

    private fun buildPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        when (tool) {
            TOOL_HIGHLIGHT -> {
                style = Paint.Style.STROKE; color = toolColor
                alpha = 110; this.strokeWidth = strokeWidth
            }
            TOOL_ERASER -> {
                style = Paint.Style.STROKE; color = Color.TRANSPARENT
                this.strokeWidth = strokeWidth
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECT -> { style = Paint.Style.STROKE; color = toolColor; this.strokeWidth = 3f }
            else -> { style = Paint.Style.STROKE; color = toolColor; this.strokeWidth = strokeWidth }
        }
    }

    private fun buildRect(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.addRect(x1, y1, x2, y2, Path.Direction.CW)
    }

    // ── Public API ────────────────────────────────────────────

    fun setTool(t: String, color: Int) { tool = t; toolColor = color; isEnabled = t != TOOL_NONE }

    fun setStrokeWidth(w: Float)  { strokeWidth = w }
    fun setTextSize(sz: Float)    { textSizePx  = sz }
    fun setColor(c: Int)          { toolColor   = c  }
    fun setPendingText(t: String) { pendingText  = t  }

    fun undo() {
        if (undoStack.isEmpty()) return
        val last = undoStack.removeLast()
        redoStack.add(last)
        when (last) {
            is Stroke    -> strokes.remove(last)
            is TextAnnot -> textAnnots.remove(last)
        }
        cacheDirty = true; invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.add(next)
        when (next) {
            is Stroke    -> { strokes.add(next); cacheCvs?.drawPath(next.path, next.paint); cacheDirty = false }
            is TextAnnot -> { textAnnots.add(next); drawTextAnnot(cacheCvs!!, next); cacheDirty = false }
        }
        invalidate()
    }

    fun clearAll() {
        strokes.clear(); textAnnots.clear(); undoStack.clear(); redoStack.clear()
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheDirty = false; invalidate()
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun hasAnnotations() = strokes.isNotEmpty() || textAnnots.isNotEmpty()
    fun getStrokes(): List<Stroke>    = strokes.toList()
    fun getTextAnnots(): List<TextAnnot> = textAnnots.toList()
    fun release() { cacheBmp?.recycle(); cacheBmp = null; cacheCvs = null }
}
