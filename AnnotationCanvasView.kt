package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * AnnotationCanvasView — per-page overlay with highlight, draw, text, shape, eraser.
 * Software layer MUST be set for PorterDuff.CLEAR (eraser) to work.
 */
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
    data class TextAnnotation(val x: Float, val y: Float, val text: String, val color: Int, val size: Float)

    private var tool         = TOOL_NONE
    private var toolColor    = Color.YELLOW
    private var strokeWidth  = 6f
    private var pendingText  : String? = null

    private val strokes      = mutableListOf<Stroke>()
    private val textAnnots   = mutableListOf<TextAnnotation>()
    private val undoStrokes  = mutableListOf<Stroke>()
    private val undoTexts    = mutableListOf<TextAnnotation>()

    private var livePath     : Path?  = null
    private var shapeX1 = 0f; private var shapeY1 = 0f
    private var shapeX2 = 0f; private var shapeY2 = 0f
    private var inShape = false

    // Off-screen cache so committed strokes don't get redrawn every frame
    private var cacheBmp : Bitmap? = null
    private var cacheCvs : Canvas? = null
    private var cacheDirty = true

    init {
        // REQUIRED for eraser (PorterDuff.CLEAR) to work correctly
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
        // Rebuild committed-stroke cache if dirty
        if (cacheDirty) {
            cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokes.forEach    { cacheCvs?.drawPath(it.path, it.paint) }
            textAnnots.forEach { drawTextAnnotation(cacheCvs!!, it) }
            cacheDirty = false
        }
        cacheBmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Live stroke preview
        livePath?.let { canvas.drawPath(it, makePaint()) }

        // Live shape preview
        if (inShape) {
            val p = Path(); buildShape(p, shapeX1, shapeY1, shapeX2, shapeY2)
            canvas.drawPath(p, makePaint())
        }
    }

    private fun drawTextAnnotation(cvs: Canvas, t: TextAnnotation) {
        // Draw text box background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color  = Color.argb(180, 255, 255, 255)
            style  = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = t.color
            textSize  = t.size
            typeface  = Typeface.DEFAULT_BOLD
        }
        val bounds = Rect()
        textPaint.getTextBounds(t.text, 0, t.text.length, bounds)
        val pad = 6f
        cvs.drawRoundRect(
            t.x - pad, t.y - bounds.height() - pad,
            t.x + bounds.width() + pad, t.y + pad,
            8f, 8f, paint
        )
        // Border
        paint.apply { color = t.color; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        cvs.drawRoundRect(
            t.x - pad, t.y - bounds.height() - pad,
            t.x + bounds.width() + pad, t.y + pad,
            8f, 8f, paint
        )
        cvs.drawText(t.text, t.x, t.y, textPaint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (tool == TOOL_NONE || !isEnabled) return false
        val x = ev.x; val y = ev.y

        try {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    undoStrokes.clear(); undoTexts.clear()
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER ->
                            livePath = Path().apply { moveTo(x, y) }
                        TOOL_RECT -> {
                            shapeX1 = x; shapeY1 = y; shapeX2 = x; shapeY2 = y; inShape = true
                        }
                        TOOL_TEXT -> {
                            // Place text at touch position
                            pendingText?.let { txt ->
                                textAnnots.add(TextAnnotation(x, y, txt, toolColor, 40f))
                                drawTextAnnotation(cacheCvs ?: return@let, textAnnots.last())
                                cacheDirty = false
                                pendingText = null
                                tool = TOOL_NONE
                                invalidate()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> when (tool) {
                    TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> livePath?.lineTo(x, y)
                    TOOL_RECT -> { shapeX2 = x; shapeY2 = y }
                }
                MotionEvent.ACTION_UP -> {
                    when (tool) {
                        TOOL_FREEHAND, TOOL_HIGHLIGHT, TOOL_ERASER -> {
                            livePath?.lineTo(x, y)
                            livePath?.let {
                                val s = Stroke(Path(it), makePaint(), tool)
                                strokes.add(s)
                                cacheCvs?.drawPath(s.path, s.paint)
                                cacheDirty = false
                            }
                            livePath = null
                        }
                        TOOL_RECT -> {
                            shapeX2 = x; shapeY2 = y
                            val p = Path(); buildShape(p, shapeX1, shapeY1, x, y)
                            val s = Stroke(p, makePaint(), tool)
                            strokes.add(s)
                            cacheCvs?.drawPath(s.path, s.paint)
                            cacheDirty = false; inShape = false
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        invalidate()
        return true
    }

    private fun makePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
        when (tool) {
            TOOL_HIGHLIGHT -> {
                style       = Paint.Style.STROKE
                color       = toolColor
                alpha       = 100
                strokeWidth = 32f
            }
            TOOL_ERASER -> {
                style       = Paint.Style.STROKE
                color       = Color.TRANSPARENT
                strokeWidth = 48f
                xfermode    = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            TOOL_RECT -> {
                style       = Paint.Style.STROKE
                color       = toolColor
                strokeWidth = 3f
            }
            else -> {  // freehand
                style       = Paint.Style.STROKE
                color       = toolColor
                strokeWidth = strokeWidth
            }
        }
    }

    private fun buildShape(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.addRect(x1, y1, x2, y2, Path.Direction.CW)
    }

    // ── Public API ────────────────────────────────────────────

    fun setTool(t: String, color: Int, width: Float = 6f) {
        tool        = t
        toolColor   = color
        strokeWidth = width
        isEnabled   = t != TOOL_NONE
    }

    fun setPendingText(text: String) { pendingText = text }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undoStrokes.add(strokes.removeLast()); cacheDirty = true; invalidate()
        } else if (textAnnots.isNotEmpty()) {
            undoTexts.add(textAnnots.removeLast()); cacheDirty = true; invalidate()
        }
    }

    fun clearAll() {
        strokes.clear(); textAnnots.clear()
        cacheCvs?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        cacheDirty = false; invalidate()
    }

    fun hasAnnotations() = strokes.isNotEmpty() || textAnnots.isNotEmpty()
    fun getStrokes(): List<Stroke> = strokes.toList()
    fun getTextAnnotations(): List<TextAnnotation> = textAnnots.toList()

    fun release() { cacheBmp?.recycle(); cacheBmp = null; cacheCvs = null }
}
