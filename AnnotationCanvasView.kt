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
        const val TOOL_MOVE_TEXT = "move_text"
    }

    data class Stroke(val path: Path, val paint: Paint, val tool: String)

    data class TextAnnot(
        var x: Float, var y: Float,
        val text: String, val color: Int, val sizePx: Float
    )

    private var tool               = TOOL_NONE
    private var toolColor          = Color.parseColor("#E53935")
    private var currentStrokeWidth = 14f
    private var currentTextSize    = 44f
    private var pendingText: String? = null

    private val strokes    = mutableListOf<Stroke>()
    private val textAnnots = mutableListOf<TextAnnot>()
    private val undoStack  = mutableListOf<Any>()
    private val redoStack  = mutableListOf<Any>()

    private var livePath: Path? = null
    private var lastX = 0f; private var lastY = 0f
    private var sx = 0f; private var sy = 0f
    private var ex = 0f; private var ey = 0f
    private var inShape = false

    private var draggedAnnot: TextAnnot? = null
    private var dragOffsetX = 0f; private var dragOffsetY = 0f

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
            strokes.forEach    { cacheCvs?.drawPath(it.path, it.paint) }
            textAnnots.forEach { drawTextAnnot(cacheCvs!!, it, it == draggedAnnot) }
            cacheDirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        livePath?.let { canvas.drawPath(it, makeLivePaint()) }
        if (inShape) {
            val p = Path().also { buildRect(it, sx, sy, ex, ey) }
            canvas.drawPath(p, makeLivePaint())
        }
    }

    private fun drawTextAnnot(cvs: Canvas, t: TextAnnot, selected: Boolean = false) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = t.color
            textSize       = t.sizePx
            typeface       = Typeface.DEFAULT
            isLinearText   = true
            isAntiAlias    = true
            isSubpixelText = true
        }
        if (selected) {
            val bounds = Rect(); tp.getTextBounds(t.text, 0, t.text.length, bounds)
            val hlPaint = Paint().apply {
                color = Color.argb(60, 33, 150, 243); style = Paint.Style.FILL
            }
            cvs.drawRoundRect(
                t.x - 6f, t.y - bounds.height() - 6f,
                t.x + bounds.width() + 6f, t.y + 6f, 4f, 4f, hlPaint
            )
        }
        cvs.drawText(t.text, t.x, t.y, tp)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestParentNonIntercept(true)
                redoStack.clear()
                when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                        livePath = Path().apply { moveTo(x, y) }
                        lastX = x; lastY = y
                    }
                    TOOL_RECT -> { sx = x; sy = y; ex = x; ey = y; inShape = true }
                    TOOL_TEXT -> pendingText?.let { txt ->
                        val ta = TextAnnot(x, y - currentTextSize * 0.3f, txt, toolColor, currentTextSize)
                        textAnnots.add(ta); undoStack.add(ta)
                        cacheCvs?.let { drawTextAnnot(it, ta) }
                        cacheDirty = false
                        pendingText = null; tool = TOOL_NONE; isEnabled = false
                    }
                    TOOL_MOVE_TEXT -> {
                        draggedAnnot = findAnnotAtPoint(x, y)
                        draggedAnnot?.let { a ->
                            dragOffsetX = x - a.x; dragOffsetY = y - a.y
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (tool) {
                TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                    livePath?.let {
                        val midX = (lastX + x) / 2f; val midY = (lastY + y) / 2f
                        it.quadTo(lastX, lastY, midX, midY)
                        lastX = x; lastY = y
                    }
                }
                TOOL_RECT -> { ex = x; ey = y }
                TOOL_MOVE_TEXT -> {
                    draggedAnnot?.let { a ->
                        a.x = x - dragOffsetX; a.y = y - dragOffsetY
                        cacheDirty = true
                    }
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
                    TOOL_RECT -> {
                        val p = Path().also { buildRect(it, sx, sy, x, y) }
                        val s = Stroke(p, makeCommittedPaint(), tool)
                        strokes.add(s); undoStack.add(s)
                        cacheCvs?.drawPath(s.path, s.paint); cacheDirty = false; inShape = false
                    }
                    TOOL_MOVE_TEXT -> draggedAnnot = null
                }
            }
        }
        invalidate(); return true
    }

    private fun findAnnotAtPoint(x: Float, y: Float): TextAnnot? {
        return textAnnots.lastOrNull { t ->
            val tp = Paint().apply { textSize = t.sizePx }
            val bounds = Rect(); tp.getTextBounds(t.text, 0, t.text.length, bounds)
            x >= t.x - 10 && x <= t.x + bounds.width() + 10 &&
            y >= t.y - bounds.height() - 10 && y <= t.y + 10
        }
    }

    private fun makeLivePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        when (tool) {
            // FIX: Highlight preserves the ACTUAL chosen color with correct alpha
            TOOL_HIGHLIGHT -> {
                color = toolColor
                // Preserve hue but force 40% alpha for translucent highlight effect
                alpha = 102  // 40% of 255
                strokeWidth = currentStrokeWidth
            }
            TOOL_ERASER -> { color = Color.parseColor("#AAAAAA"); alpha = 80; strokeWidth = currentStrokeWidth }
            TOOL_RECT   -> { color = toolColor; strokeWidth = 2.5f; alpha = 255 }
            else        -> { color = toolColor; strokeWidth = currentStrokeWidth; alpha = 255 }
        }
    }

    private fun makeCommittedPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
        when (tool) {
            // FIX: Highlight - store full color + correct alpha in the Paint
            TOOL_HIGHLIGHT -> {
                // Store the base color (no alpha) separately, set alpha=102
                val baseColor = toolColor or 0xFF000000.toInt()  // ensure opaque base
                color = baseColor; alpha = 102; strokeWidth = currentStrokeWidth
            }
            TOOL_ERASER -> {
                color = Color.TRANSPARENT; strokeWidth = currentStrokeWidth
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECT   -> { color = toolColor; strokeWidth = 2.5f; alpha = 255 }
            else        -> { color = toolColor; strokeWidth = currentStrokeWidth; alpha = 255 }
        }
    }

    private fun buildRect(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.addRect(x1, y1, x2, y2, Path.Direction.CW)
    }

    private fun requestParentNonIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) { p.requestDisallowInterceptTouchEvent(disallow); p = p.parent }
    }

    // Public API
    fun setTool(t: String, color: Int) {
        tool = t; toolColor = color; isEnabled = (t != TOOL_NONE)
    }
    fun setStrokeWidth(w: Float) { currentStrokeWidth = w.coerceAtLeast(2f) }
    fun setTextSize(sz: Float)   { currentTextSize    = sz.coerceAtLeast(12f) }
    fun setColor(c: Int)         { toolColor = c }
    fun setPendingText(t: String){ pendingText = t; isEnabled = true }

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
            is TextAnnot -> { textAnnots.add(next); cacheCvs?.let { drawTextAnnot(it, next) }; cacheDirty = false }
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
