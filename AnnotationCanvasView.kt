package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.propdf.editor.data.model.AnnotationType

/**
 * AnnotationCanvasView - Custom View for Drawing Annotations
 *
 * Handles:
 * - Freehand drawing (pen tool)
 * - Highlight / underline / strikethrough (text markup)
 * - Shapes: rectangle, circle, arrow, line
 * - Eraser tool
 * - Undo / redo stack
 * - Color and stroke width control
 *
 * Works on API 16+
 */
class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Drawing State ────────────────────────────────────────────
    private val paths = mutableListOf<DrawPath>()       // Completed strokes
    private val undoStack = mutableListOf<DrawPath>()   // For undo
    private var currentPath: Path? = null
    private var startX = 0f
    private var startY = 0f

    // ── Current Tool Settings ─────────────────────────────────────
    private var currentTool = AnnotationType.NONE
    private var currentColor = Color.YELLOW
    private var strokeWidth = 8f
    private var alpha = 128 // Semi-transparent for highlights

    // ── Paint Objects ─────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@AnnotationCanvasView.strokeWidth
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        alpha = 128
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // ── Bitmap Buffer ─────────────────────────────────────────────
    private var canvasBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawingCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw completed paths
        for (dp in paths) {
            canvas.drawPath(dp.path, dp.paint)
        }

        // Draw current path being drawn
        currentPath?.let {
            canvas.drawPath(it, getCurrentPaint())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentTool == AnnotationType.NONE) return false

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> onTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> onTouchMove(x, y)
            MotionEvent.ACTION_UP   -> onTouchUp(x, y)
        }

        invalidate()
        return true
    }

    private fun onTouchDown(x: Float, y: Float) {
        startX = x
        startY = y
        currentPath = Path().apply { moveTo(x, y) }
    }

    private fun onTouchMove(x: Float, y: Float) {
        when (currentTool) {
            AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT,
            AnnotationType.UNDERLINE, AnnotationType.STRIKETHROUGH -> {
                currentPath?.quadTo(startX, startY, (x + startX) / 2, (y + startY) / 2)
                startX = x
                startY = y
            }
            AnnotationType.RECTANGLE, AnnotationType.ARROW,
            AnnotationType.CIRCLE -> {
                // Shapes are redrawn each move - clear and redraw
                currentPath = buildShapePath(startX, startY, x, y)
            }
            AnnotationType.ERASER -> {
                // Erase paths near touch point
                erasePaths(x, y)
            }
            else -> {}
        }
    }

    private fun onTouchUp(x: Float, y: Float) {
        when (currentTool) {
            AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT,
            AnnotationType.UNDERLINE, AnnotationType.STRIKETHROUGH -> {
                currentPath?.lineTo(x, y)
                commitCurrentPath()
            }
            AnnotationType.RECTANGLE, AnnotationType.ARROW,
            AnnotationType.CIRCLE -> {
                currentPath = buildShapePath(startX, startY, x, y)
                commitCurrentPath()
            }
            AnnotationType.STICKY_NOTE -> {
                showStickyNoteDialog(x, y)
            }
            else -> {}
        }
        currentPath = null
    }

    private fun buildShapePath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        return Path().apply {
            when (currentTool) {
                AnnotationType.RECTANGLE -> {
                    addRect(x1, y1, x2, y2, Path.Direction.CW)
                }
                AnnotationType.CIRCLE -> {
                    addOval(RectF(x1, y1, x2, y2), Path.Direction.CW)
                }
                AnnotationType.ARROW -> {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                    // Arrow head
                    val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    val arrowLen = 30f
                    moveTo(x2, y2)
                    lineTo(
                        (x2 - arrowLen * Math.cos(angle - Math.PI / 6)).toFloat(),
                        (y2 - arrowLen * Math.sin(angle - Math.PI / 6)).toFloat()
                    )
                    moveTo(x2, y2)
                    lineTo(
                        (x2 - arrowLen * Math.cos(angle + Math.PI / 6)).toFloat(),
                        (y2 - arrowLen * Math.sin(angle + Math.PI / 6)).toFloat()
                    )
                }
                else -> {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
            }
        }
    }

    private fun getCurrentPaint(): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            color = currentColor
            this.alpha = when (currentTool) {
                AnnotationType.HIGHLIGHT -> 100
                AnnotationType.ERASER -> 255
                else -> 220
            }
            style = when (currentTool) {
                AnnotationType.ERASER -> Paint.Style.STROKE
                else -> Paint.Style.STROKE
            }
            strokeWidth = when (currentTool) {
                AnnotationType.FREEHAND -> this@AnnotationCanvasView.strokeWidth
                AnnotationType.HIGHLIGHT -> 30f
                AnnotationType.UNDERLINE, AnnotationType.STRIKETHROUGH -> 5f
                AnnotationType.ERASER -> 40f
                else -> 4f
            }
            if (currentTool == AnnotationType.ERASER) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private fun commitCurrentPath() {
        currentPath?.let {
            val dp = DrawPath(Path(it), getCurrentPaint())
            paths.add(dp)
            undoStack.clear()
        }
    }

    private fun erasePaths(x: Float, y: Float) {
        val eraseRadius = 40f
        val eraseRect = RectF(x - eraseRadius, y - eraseRadius, x + eraseRadius, y + eraseRadius)
        paths.removeAll { dp ->
            val bounds = RectF()
            dp.path.computeBounds(bounds, true)
            RectF.intersects(bounds, eraseRect)
        }
    }

    private fun showStickyNoteDialog(x: Float, y: Float) {
        val context = context
        val input = android.widget.EditText(context).apply {
            hint = "Enter note text..."
            setPadding(32, 16, 32, 16)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Add Note")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    drawStickyNote(x, y, text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun drawStickyNote(x: Float, y: Float, text: String) {
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF176")
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
        }
        val noteRect = RectF(x, y, x + 200, y + 120)
        drawingCanvas?.drawRoundRect(noteRect, 8f, 8f, notePaint)
        drawingCanvas?.drawText(text, x + 10, y + 60, textPaint)
        invalidate()
    }

    // ── Public API ───────────────────────────────────────────────

    fun setTool(tool: AnnotationType, color: Int) {
        currentTool = tool
        currentColor = color
    }

    fun setStrokeWidth(width: Float) {
        strokeWidth = width
    }

    fun setColor(color: Int) {
        currentColor = color
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undoStack.add(paths.removeLast())
            invalidate()
        }
    }

    fun redo() {
        if (undoStack.isNotEmpty()) {
            paths.add(undoStack.removeLast())
            invalidate()
        }
    }

    fun clearAll() {
        paths.clear()
        undoStack.clear()
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun exportAnnotations(): Bitmap? {
        return canvasBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    // ── Data class for a stored path ─────────────────────────────
    data class DrawPath(val path: Path, val paint: Paint)
}
