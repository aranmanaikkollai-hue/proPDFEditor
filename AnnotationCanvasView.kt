package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class AnnotationType {
    NONE, HIGHLIGHT, UNDERLINE, STRIKETHROUGH,
    FREEHAND, STICKY_NOTE, RECTANGLE, CIRCLE,
    ARROW, LINE, ERASER, STAMP, TEXT_BOX
}

class AnnotationCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paths = mutableListOf<DrawPath>()
    private val undoStack = mutableListOf<DrawPath>()
    private var currentPath: Path? = null
    private var startX = 0f
    private var startY = 0f
    private var currentToolName = "none"
    private var currentColor = Color.YELLOW
    private var strokeWidth = 8f

    data class DrawPath(val path: Path, val paint: Paint)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (dp in paths) canvas.drawPath(dp.path, dp.paint)
        currentPath?.let { canvas.drawPath(it, makePaint()) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentToolName == "none") return false
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x; startY = y
                currentPath = Path().apply { moveTo(x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.quadTo(startX, startY, (x + startX) / 2, (y + startY) / 2)
                startX = x; startY = y
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.lineTo(x, y)
                currentPath?.let { paths.add(DrawPath(Path(it), makePaint())); undoStack.clear() }
                currentPath = null
            }
        }
        invalidate(); return true
    }

    private fun makePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentColor
        alpha = if (currentToolName == "highlight") 100 else 220
        style = if (currentToolName == "eraser") Paint.Style.STROKE else Paint.Style.STROKE
        this.strokeWidth = when (currentToolName) {
            "highlight" -> 30f
            "eraser"    -> 50f
            else        -> this@AnnotationCanvasView.strokeWidth
        }
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (currentToolName == "eraser") {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }

    // Accept String tool name — matches what ViewerActivity passes
    fun setTool(toolName: String, color: Int) {
        currentToolName = toolName
        currentColor = color
    }

    fun setStrokeWidth(w: Float) { strokeWidth = w }
    fun undo() { if (paths.isNotEmpty()) { undoStack.add(paths.removeLast()); invalidate() } }
    fun redo() { if (undoStack.isNotEmpty()) { paths.add(undoStack.removeLast()); invalidate() } }
    fun clearAll() { paths.clear(); undoStack.clear(); invalidate() }
}
