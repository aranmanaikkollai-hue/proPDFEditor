package com.propdf.viewer.gesture

import android.annotation.SuppressLint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.Stroke
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import com.propdf.viewer.annotation.model.TextMarkupAnnotation
import com.propdf.viewer.coords.PdfCoordinateSpace
import com.propdf.viewer.render.RenderScheduler
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Single gesture entry point that eliminates conflicts between viewer pan/zoom
 * and annotation drawing. Mode-based dispatch ensures no event fighting.
 *
 * - VIEWER mode: all touches fall through to PremiumPageView pan/zoom
 * - ANNOTATION mode: touches are converted to PDF coordinates and routed to
 *   annotation operations (draw, select, move, erase)
 */
class UnifiedGestureCoordinator(
    private val annotationManager: AnnotationManager,
    private val renderScheduler: RenderScheduler,
    private val coordinateSpace: PdfCoordinateSpace
) : View.OnTouchListener {

    enum class Mode { VIEWER, ANNOTATION }

    enum class ToolMode {
        NONE, SELECT, ERASER,
        HIGHLIGHT, UNDERLINE, STRIKEOUT,
        PENCIL, FREEHAND,
        TEXT_COMMENT, STICKY_NOTE,
        ARROW, RECTANGLE, CIRCLE,
        SIGNATURE, IMAGE_STAMP
    }

    private var mode = Mode.VIEWER
    private var tool = ToolMode.NONE

    private var touchState = TouchState.IDLE
    private var currentStroke: Stroke? = null
    private var currentInk: InkAnnotation? = null
    private var currentShape: ShapeAnnotation? = null
    private var currentSignature: SignatureAnnotation? = null
    private var currentMarkup: TextMarkupAnnotation? = null
    private var moveAnnotationId: String? = null
    private var moveStartPdf: PointF? = null
    private var pageIndex: Int = 0

    private var currentColor: Int = android.graphics.Color.YELLOW
    private var currentStrokeWidth: Float = 3f

    private var targetView: View? = null

    private enum class TouchState { IDLE, DRAWING, MOVING }

    companion object {
        private const val MOVE_THRESHOLD_NORM = 0.008f
        private const val ERASER_TOLERANCE = 0.025f
        private const val MIN_SHAPE_SIZE = 0.005f
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        if (mode == Mode.VIEWER) {
            annotationManager.deselectAll()
            cancelDrawing()
        }
    }

    fun setTool(tool: ToolMode) {
        this.tool = tool
        annotationManager.deselectAll()
        cancelDrawing()
    }

    fun setColor(color: Int) { currentColor = color }
    fun setStrokeWidth(width: Float) { currentStrokeWidth = width.coerceIn(0.5f, 20f) }
    fun setPageIndex(index: Int) { pageIndex = index }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (mode == Mode.VIEWER) return false

        val pdfPoint = coordinateSpace.screenToPdf(event.x, event.y)
        val nx = pdfPoint.x
        val ny = pdfPoint.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return handleDown(nx, ny, event.pressure)
            MotionEvent.ACTION_MOVE -> return handleMove(nx, ny, event.pressure, v)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleUp()
                return true
            }
        }
        return false
    }

    private fun handleDown(nx: Float, ny: Float, pressure: Float): Boolean {
        when (tool) {
            ToolMode.NONE, ToolMode.SELECT -> {
                val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = 0.02f)
                annotationManager.selectAnnotation(hit?.id)
                if (hit != null) {
                    moveAnnotationId = hit.id
                    moveStartPdf = PointF(nx, ny)
                    touchState = TouchState.MOVING
                } else {
                    return false // fall through to viewer pan/zoom
                }
                targetView?.let { renderScheduler.scheduleImmediate(it) }
                return true
            }
            ToolMode.ERASER -> {
                val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = ERASER_TOLERANCE)
                hit?.let { annotationManager.removeAnnotation(it.id) }
                targetView?.let { renderScheduler.scheduleImmediate(it) }
                return true
            }
            ToolMode.TEXT_COMMENT -> {
                val ann = TextCommentAnnotation(
                    pageIndex = pageIndex, anchorX = nx, anchorY = ny, color = currentColor
                )
                annotationManager.addAnnotation(ann)
                targetView?.let { renderScheduler.scheduleImmediate(it) }
                return true
            }
            ToolMode.STICKY_NOTE -> {
                val ann = StickyNoteAnnotation(
                    pageIndex = pageIndex, x = nx, y = ny, color = currentColor
                )
                annotationManager.addAnnotation(ann)
                targetView?.let { renderScheduler.scheduleImmediate(it) }
                return true
            }
            ToolMode.IMAGE_STAMP -> {
                val ann = ImageStampAnnotation(
                    pageIndex = pageIndex, x = nx, y = ny, color = currentColor
                )
                annotationManager.addAnnotation(ann)
                targetView?.let { renderScheduler.scheduleImmediate(it) }
                return true
            }
            else -> {
                startDrawing(nx, ny, pressure)
                touchState = TouchState.DRAWING
                return true
            }
        }
    }

    private fun handleMove(nx: Float, ny: Float, pressure: Float, view: View): Boolean {
        when (touchState) {
            TouchState.MOVING -> {
                val start = moveStartPdf ?: return false
                val dx = nx - start.x
                val dy = ny - start.y
                val id = moveAnnotationId ?: return false
                if (abs(dx) > MOVE_THRESHOLD_NORM || abs(dy) > MOVE_THRESHOLD_NORM) {
                    annotationManager.moveAnnotation(id, dx, dy)
                    moveStartPdf = PointF(nx, ny)
                    renderScheduler.schedule(view)
                }
                return true
            }
            TouchState.DRAWING -> {
                when (tool) {
                    ToolMode.PENCIL, ToolMode.FREEHAND -> {
                        val stroke = currentStroke ?: return false
                        val newStroke = stroke.addPoint(PointF(nx, ny), pressure)
                        currentStroke = newStroke
                        currentInk?.let { ink ->
                            val updated = ink.copy(strokes = ink.strokes.dropLast(1) + newStroke)
                            currentInk = updated
                            annotationManager.updateAnnotation(updated)
                            renderScheduler.schedule(view)
                        }
                    }
                    ToolMode.ARROW, ToolMode.RECTANGLE, ToolMode.CIRCLE -> {
                        val start = currentShape?.rectBounds?.let {
                            PointF(it.left, it.top)
                        } ?: return false
                        val rect = RectF(
                            min(start.x, nx), min(start.y, ny),
                            max(start.x, nx), max(start.y, ny)
                        )
                        currentShape?.let { shape ->
                            val updated = shape.copy(rectBounds = rect)
                            currentShape = updated
                            annotationManager.updateAnnotation(updated)
                            renderScheduler.schedule(view)
                        }
                    }
                    ToolMode.HIGHLIGHT, ToolMode.UNDERLINE, ToolMode.STRIKEOUT -> {
                        val start = currentMarkup?.rectBounds?.let {
                            PointF(it.left, it.top)
                        } ?: return false
                        val rect = RectF(
                            min(start.x, nx), min(start.y, ny),
                            max(start.x, nx), max(start.y, ny)
                        )
                        currentMarkup?.let { markup ->
                            val updated = markup.copy(rectBounds = rect)
                            currentMarkup = updated
                            annotationManager.updateAnnotation(updated)
                            renderScheduler.schedule(view)
                        }
                    }
                    ToolMode.SIGNATURE -> {
                        val stroke = currentStroke ?: return false
                        val newStroke = stroke.addPoint(PointF(nx, ny), pressure)
                        currentStroke = newStroke
                        currentSignature?.let { sig ->
                            val updatedStrokes = sig.strokes.dropLast(1) + newStroke
                            val bounds = calculateStrokeBounds(updatedStrokes)
                            val updated = sig.copy(
                                strokes = updatedStrokes,
                                rectBounds = bounds
                            )
                            currentSignature = updated
                            annotationManager.updateAnnotation(updated)
                            renderScheduler.schedule(view)
                        }
                    }
                    else -> {}
                }
                return true
            }
            else -> return true
        }
    }

    private fun handleUp() {
        when (touchState) {
            TouchState.DRAWING -> finalizeDrawing()
            TouchState.MOVING -> {
                moveAnnotationId = null
                moveStartPdf = null
            }
            else -> {}
        }
        touchState = TouchState.IDLE
    }

    private fun startDrawing(nx: Float, ny: Float, pressure: Float) {
        val point = PointF(nx, ny)
        when (tool) {
            ToolMode.PENCIL, ToolMode.FREEHAND -> {
                val stroke = Stroke(points = listOf(point), pressures = listOf(pressure))
                currentStroke = stroke
                val ann = InkAnnotation(
                    pageIndex = pageIndex,
                    type = if (tool == ToolMode.PENCIL) AnnotationType.PENCIL else AnnotationType.FREEHAND,
                    color = currentColor,
                    strokeWidth = currentStrokeWidth,
                    strokes = listOf(stroke)
                )
                currentInk = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.ARROW -> {
                val ann = ShapeAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.ARROW,
                    color = currentColor, strokeWidth = currentStrokeWidth,
                    rectBounds = RectF(nx, ny, nx, ny), endArrow = true
                )
                currentShape = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.RECTANGLE -> {
                val ann = ShapeAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.RECTANGLE,
                    color = currentColor, strokeWidth = currentStrokeWidth,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentShape = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.CIRCLE -> {
                val ann = ShapeAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.CIRCLE,
                    color = currentColor, strokeWidth = currentStrokeWidth,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentShape = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.HIGHLIGHT -> {
                val ann = TextMarkupAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.HIGHLIGHT,
                    color = currentColor, alpha = 0.3f,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentMarkup = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.UNDERLINE -> {
                val ann = TextMarkupAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.UNDERLINE,
                    color = currentColor, alpha = 1.0f,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentMarkup = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.STRIKEOUT -> {
                val ann = TextMarkupAnnotation(
                    pageIndex = pageIndex, type = AnnotationType.STRIKEOUT,
                    color = currentColor, alpha = 1.0f,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentMarkup = ann
                annotationManager.addAnnotation(ann)
            }
            ToolMode.SIGNATURE -> {
                val stroke = Stroke(points = listOf(point), pressures = listOf(pressure))
                currentStroke = stroke
                val ann = SignatureAnnotation(
                    pageIndex = pageIndex, strokes = listOf(stroke),
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentSignature = ann
                annotationManager.addAnnotation(ann)
            }
            else -> {}
        }
    }

    private fun finalizeDrawing() {
        when (tool) {
            ToolMode.PENCIL, ToolMode.FREEHAND -> {
                currentInk?.let { ink ->
                    if (ink.strokes.isEmpty() || ink.strokes.all { it.points.size < 2 }) {
                        annotationManager.removeAnnotation(ink.id)
                    }
                }
                currentInk = null
                currentStroke = null
            }
            ToolMode.ARROW, ToolMode.RECTANGLE, ToolMode.CIRCLE -> {
                currentShape?.let { shape ->
                    val r = shape.rectBounds
                    if (r.width() < MIN_SHAPE_SIZE || r.height() < MIN_SHAPE_SIZE) {
                        annotationManager.removeAnnotation(shape.id)
                    }
                }
                currentShape = null
            }
            ToolMode.HIGHLIGHT, ToolMode.UNDERLINE, ToolMode.STRIKEOUT -> {
                currentMarkup?.let { markup ->
                    val r = markup.rectBounds
                    if (r.width() < MIN_SHAPE_SIZE || r.height() < MIN_SHAPE_SIZE) {
                        annotationManager.removeAnnotation(markup.id)
                    }
                }
                currentMarkup = null
            }
            ToolMode.SIGNATURE -> {
                currentSignature?.let { sig ->
                    if (sig.strokes.isEmpty() || sig.strokes.all { it.points.size < 2 }) {
                        annotationManager.removeAnnotation(sig.id)
                    }
                }
                currentSignature = null
                currentStroke = null
            }
            else -> {}
        }
    }

    private fun cancelDrawing() {
        currentInk?.let { annotationManager.removeAnnotation(it.id) }
        currentShape?.let { annotationManager.removeAnnotation(it.id) }
        currentMarkup?.let { annotationManager.removeAnnotation(it.id) }
        currentSignature?.let { annotationManager.removeAnnotation(it.id) }
        currentInk = null
        currentShape = null
        currentMarkup = null
        currentSignature = null
        currentStroke = null
        moveAnnotationId = null
        moveStartPdf = null
        touchState = TouchState.IDLE
    }

    private fun calculateStrokeBounds(strokes: List<Stroke>): RectF {
        if (strokes.isEmpty()) return RectF()
        val allPoints = strokes.flatMap { it.points }
        val minX = allPoints.minOfOrNull { it.x } ?: 0f
        val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
        val minY = allPoints.minOfOrNull { it.y } ?: 0f
        val maxY = allPoints.maxOfOrNull { it.y } ?: 0f
        return RectF(minX, minY, maxX, maxY)
    }

    /** Attach this coordinator to a PremiumPageView. */
    fun attachTo(view: View) {
        targetView = view
        view.setOnTouchListener(this)
    }

    /** Detach and clean up. */
    fun detach() {
        targetView?.setOnTouchListener(null)
        targetView = null
        cancelDrawing()
    }
}
