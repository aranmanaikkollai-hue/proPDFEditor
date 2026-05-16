package com.propdf.viewer.annotation.touch

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.model.Annotation
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.Stroke
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot

class AnnotationTouchEngine(
    private val context: Context,
    private val annotationManager: AnnotationManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    enum class ToolMode {
        NONE,
        HIGHLIGHT,
        UNDERLINE,
        STRIKEOUT,
        PENCIL,
        FREEHAND,
        TEXT_COMMENT,
        STICKY_NOTE,
        ARROW,
        RECTANGLE,
        CIRCLE,
        SIGNATURE,
        IMAGE_STAMP,
        SELECT,
        ERASER
    }

    enum class TouchState {
        IDLE,
        DRAWING,
        MOVING,
        RESIZING,
        SCROLLING
    }

    private val _currentTool = MutableStateFlow(ToolMode.NONE)
    val currentTool: StateFlow<ToolMode> = _currentTool.asStateFlow()

    private val _touchState = MutableStateFlow(TouchState.IDLE)
    val touchState: StateFlow<TouchState> = _touchState.asStateFlow()

    private val _currentColor = MutableStateFlow(android.graphics.Color.YELLOW)
    val currentColor: StateFlow<Int> = _currentColor.asStateFlow()

    private val _currentStrokeWidth = MutableStateFlow(3f)
    val currentStrokeWidth: StateFlow<Float> = _currentStrokeWidth.asStateFlow()

    private val pressureBuffer = ArrayDeque<Float>(PRESSURE_BUFFER_SIZE)
    private val pointBuffer = ArrayDeque<PointF>(POINT_BUFFER_SIZE)

    private var currentStroke: Stroke? = null
    private var currentInkAnnotation: InkAnnotation? = null
    private var currentShapeAnnotation: ShapeAnnotation? = null
    private var currentSignatureAnnotation: SignatureAnnotation? = null

    private var moveStartPoint: PointF? = null
    private var moveStartAnnotation: Annotation? = null

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    var onRequestViewerScroll: (() -> Boolean)? = null
    var onRequestViewerZoom: (() -> Boolean)? = null
    var onAnnotationCreated: ((Annotation) -> Unit)? = null
    var onAnnotationSelected: ((Annotation?) -> Unit)? = null

    var pageWidth: Float = 1f
    var pageHeight: Float = 1f
    var pageIndex: Int = 0

    companion object {
        private const val PRESSURE_BUFFER_SIZE = 5
        private const val POINT_BUFFER_SIZE = 8
        private const val MOVE_THRESHOLD_DP = 8f
        private const val PALM_REJECTION_PRESSURE = 0.15f
        private const val SMOOTHING_FACTOR = 0.7f
    }

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (_currentTool.value == ToolMode.NONE) {
                    return false
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress(e)
            }
        })

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (_touchState.value != TouchState.DRAWING) {
                    return false
                }
                return true
            }
        })
    }

    fun setTool(tool: ToolMode) {
        _currentTool.value = tool
        annotationManager.deselectAll()
        _touchState.value = TouchState.IDLE
    }

    fun setColor(color: Int) {
        _currentColor.value = color
    }

    fun setStrokeWidth(width: Float) {
        _currentStrokeWidth.value = width.coerceIn(0.5f, 20f)
    }

    fun onTouchEvent(event: MotionEvent, view: View): Boolean {
        val gestureHandled = gestureDetector.onTouchEvent(event)
        val scaleHandled = scaleDetector.onTouchEvent(event)

        if (gestureHandled || scaleHandled) return true

        val normalizedX = event.x / view.width
        val normalizedY = event.y / view.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return handleActionDown(event, normalizedX, normalizedY)
            }
            MotionEvent.ACTION_MOVE -> {
                return handleActionMove(event, normalizedX, normalizedY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return handleActionUp(event, normalizedX, normalizedY)
            }
        }
        return false
    }

    private fun handleActionDown(event: MotionEvent, nx: Float, ny: Float): Boolean {
        val tool = _currentTool.value

        when (tool) {
            ToolMode.NONE -> {
                val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = 0.02f)
                if (hit != null) {
                    annotationManager.selectAnnotation(hit.id)
                    moveStartAnnotation = hit
                    moveStartPoint = PointF(nx, ny)
                    _touchState.value = TouchState.MOVING
                    onAnnotationSelected?.invoke(hit)
                    return true
                }
                return false
            }
            ToolMode.SELECT -> {
                val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = 0.02f)
                annotationManager.selectAnnotation(hit?.id)
                onAnnotationSelected?.invoke(hit)
                return true
            }
            ToolMode.ERASER -> {
                val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = 0.03f)
                hit?.let { ann -> annotationManager.removeAnnotation(ann.id) }
                return true
            }
            ToolMode.TEXT_COMMENT, ToolMode.STICKY_NOTE, ToolMode.IMAGE_STAMP -> {
                if (tool == ToolMode.TEXT_COMMENT) {
                    val annotation = TextCommentAnnotation(
                        pageIndex = pageIndex,
                        anchorX = nx,
                        anchorY = ny,
                        color = _currentColor.value
                    )
                    annotationManager.addAnnotation(annotation)
                    onAnnotationCreated?.invoke(annotation)
                } else if (tool == ToolMode.STICKY_NOTE) {
                    val annotation = StickyNoteAnnotation(
                        pageIndex = pageIndex,
                        x = nx,
                        y = ny,
                        color = _currentColor.value
                    )
                    annotationManager.addAnnotation(annotation)
                    onAnnotationCreated?.invoke(annotation)
                }
                return true
            }
            else -> {
                startDrawing(nx, ny, event.pressure)
                return true
            }
        }
    }

    private fun handleActionMove(event: MotionEvent, nx: Float, ny: Float): Boolean {
        val tool = _currentTool.value

        when (_touchState.value) {
            TouchState.MOVING -> {
                val start = moveStartPoint ?: return false
                val dx = nx - start.x
                val dy = ny - start.y
                val annotation = moveStartAnnotation ?: return false

                val threshold = MOVE_THRESHOLD_DP / 1000f
                if (abs(dx) > threshold || abs(dy) > threshold) {
                    annotationManager.moveAnnotation(annotation.id, dx, dy)
                    moveStartPoint = PointF(nx, ny)
                }
                return true
            }
            TouchState.DRAWING -> {
                val pressure = smoothPressure(event.pressure)
                val point = smoothPoint(PointF(nx, ny))

                if (pressure < PALM_REJECTION_PRESSURE) return true

                when (tool) {
                    ToolMode.PENCIL, ToolMode.FREEHAND -> {
                        val stroke = currentStroke ?: return false
                        val newStroke = stroke.addPoint(point, pressure)
                        currentStroke = newStroke
                        currentInkAnnotation?.let { ink ->
                            val updatedStrokes = ink.strokes.dropLast(1) + newStroke
                            val updated = ink.copy(strokes = updatedStrokes)
                            currentInkAnnotation = updated
                            annotationManager.updateAnnotation(updated)
                        }
                    }
                    ToolMode.ARROW, ToolMode.RECTANGLE, ToolMode.CIRCLE -> {
                        val start = pointBuffer.firstOrNull() ?: return false
                        val rectBounds = RectF(
                            minOf(start.x, point.x),
                            minOf(start.y, point.y),
                            maxOf(start.x, point.x),
                            maxOf(start.y, point.y)
                        )
                        currentShapeAnnotation?.let { shape ->
                            val updated = shape.copy(rectBounds = rectBounds)
                            currentShapeAnnotation = updated
                            annotationManager.updateAnnotation(updated)
                        }
                    }
                    ToolMode.SIGNATURE -> {
                        val stroke = currentStroke ?: return false
                        val newStroke = stroke.addPoint(point, pressure)
                        currentStroke = newStroke
                        currentSignatureAnnotation?.let { sig ->
                            val updatedStrokes = sig.strokes.dropLast(1) + newStroke
                            val updated = sig.copy(
                                strokes = updatedStrokes,
                                rectBounds = calculateBounds(updatedStrokes)
                            )
                            currentSignatureAnnotation = updated
                            annotationManager.updateAnnotation(updated)
                        }
                    }
                    else -> {}
                }
                return true
            }
            else -> {
                if (tool != ToolMode.NONE && tool != ToolMode.SELECT && tool != ToolMode.ERASER) {
                    val start = pointBuffer.firstOrNull()
                    if (start != null) {
                        val dist = hypot(nx - start.x, ny - start.y)
                        if (dist > MOVE_THRESHOLD_DP / 1000f) {
                            startDrawing(start.x, start.y, event.pressure)
                        }
                    }
                }
                return true
            }
        }
    }

    private fun handleActionUp(event: MotionEvent, nx: Float, ny: Float): Boolean {
        when (_touchState.value) {
            TouchState.DRAWING -> {
                finalizeDrawing()
            }
            TouchState.MOVING -> {
                moveStartPoint = null
                moveStartAnnotation = null
            }
            else -> {}
        }

        pointBuffer.clear()
        pressureBuffer.clear()
        _touchState.value = TouchState.IDLE
        return true
    }

    private fun startDrawing(nx: Float, ny: Float, pressure: Float) {
        _touchState.value = TouchState.DRAWING
        pointBuffer.clear()
        pressureBuffer.clear()
        pointBuffer.addLast(PointF(nx, ny))
        pressureBuffer.addLast(pressure)

        val tool = _currentTool.value
        val point = PointF(nx, ny)

        when (tool) {
            ToolMode.PENCIL, ToolMode.FREEHAND -> {
                val stroke = Stroke(points = listOf(point), pressures = listOf(pressure))
                currentStroke = stroke
                val annotation = InkAnnotation(
                    pageIndex = pageIndex,
                    type = if (tool == ToolMode.PENCIL) AnnotationType.PENCIL else AnnotationType.FREEHAND,
                    color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value,
                    strokes = listOf(stroke)
                )
                currentInkAnnotation = annotation
                annotationManager.addAnnotation(annotation)
            }
            ToolMode.ARROW -> {
                val annotation = ShapeAnnotation(
                    pageIndex = pageIndex,
                    type = AnnotationType.ARROW,
                    color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value,
                    rectBounds = RectF(nx, ny, nx, ny),
                    endArrow = true
                )
                currentShapeAnnotation = annotation
                annotationManager.addAnnotation(annotation)
            }
            ToolMode.RECTANGLE -> {
                val annotation = ShapeAnnotation(
                    pageIndex = pageIndex,
                    type = AnnotationType.RECTANGLE,
                    color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentShapeAnnotation = annotation
                annotationManager.addAnnotation(annotation)
            }
            ToolMode.CIRCLE -> {
                val annotation = ShapeAnnotation(
                    pageIndex = pageIndex,
                    type = AnnotationType.CIRCLE,
                    color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value,
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentShapeAnnotation = annotation
                annotationManager.addAnnotation(annotation)
            }
            ToolMode.SIGNATURE -> {
                val stroke = Stroke(points = listOf(point), pressures = listOf(pressure))
                currentStroke = stroke
                val annotation = SignatureAnnotation(
                    pageIndex = pageIndex,
                    strokes = listOf(stroke),
                    rectBounds = RectF(nx, ny, nx, ny)
                )
                currentSignatureAnnotation = annotation
                annotationManager.addAnnotation(annotation)
            }
            else -> {}
        }
    }

    private fun finalizeDrawing() {
        val tool = _currentTool.value

        when (tool) {
            ToolMode.PENCIL, ToolMode.FREEHAND -> {
                currentInkAnnotation?.let { ink ->
                    if (ink.strokes.isEmpty() || ink.strokes.all { stroke: Stroke -> stroke.points.size < 2 }) {
                        annotationManager.removeAnnotation(ink.id)
                    } else {
                        onAnnotationCreated?.invoke(ink)
                    }
                }
                currentInkAnnotation = null
                currentStroke = null
            }
            ToolMode.ARROW, ToolMode.RECTANGLE, ToolMode.CIRCLE -> {
                currentShapeAnnotation?.let { shape ->
                    val rectBounds = shape.rectBounds
                    if (rectBounds.width() < 0.005f || rectBounds.height() < 0.005f) {
                        annotationManager.removeAnnotation(shape.id)
                    } else {
                        onAnnotationCreated?.invoke(shape)
                    }
                }
                currentShapeAnnotation = null
            }
            ToolMode.SIGNATURE -> {
                currentSignatureAnnotation?.let { sig ->
                    if (sig.strokes.isEmpty() || sig.strokes.all { stroke: Stroke -> stroke.points.size < 2 }) {
                        annotationManager.removeAnnotation(sig.id)
                    } else {
                        onAnnotationCreated?.invoke(sig)
                    }
                }
                currentSignatureAnnotation = null
                currentStroke = null
            }
            else -> {}
        }
    }

    private fun smoothPressure(raw: Float): Float {
        pressureBuffer.addLast(raw.coerceIn(0.1f, 1.0f))
        if (pressureBuffer.size > PRESSURE_BUFFER_SIZE) {
            pressureBuffer.removeFirst()
        }

        val weights = listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.35f)
        var sum = 0f
        var weightSum = 0f
        pressureBuffer.forEachIndexed { index: Int, p: Float ->
            val w = weights.getOrElse(index) { 0.2f }
            sum += p * w
            weightSum += w
        }
        return if (weightSum > 0) sum / weightSum else raw
    }

    private fun smoothPoint(raw: PointF): PointF {
        pointBuffer.addLast(raw)
        if (pointBuffer.size > POINT_BUFFER_SIZE) {
            pointBuffer.removeFirst()
        }

        var smoothedX = raw.x
        var smoothedY = raw.y
        val alpha = SMOOTHING_FACTOR

        pointBuffer.reversed().forEachIndexed { index: Int, pt: PointF ->
            val weight = alpha * (1 - alpha).pow(index)
            smoothedX += weight * (pt.x - smoothedX)
            smoothedY += weight * (pt.y - smoothedY)
        }

        return PointF(smoothedX, smoothedY)
    }

    private fun Float.pow(exp: Int): Float {
        var result = 1f
        repeat(exp) { result *= this }
        return result
    }

    private fun handleLongPress(event: MotionEvent) {
        val nx = event.x / pageWidth
        val ny = event.y / pageHeight
        val hit = annotationManager.hitTest(pageIndex, nx, ny, tolerance = 0.02f)
        hit?.let { ann ->
            annotationManager.selectAnnotation(ann.id)
        }
    }

    private fun calculateBounds(strokes: List<Stroke>): RectF {
        if (strokes.isEmpty()) return RectF()
        val allPoints = strokes.flatMap { stroke: Stroke -> stroke.points }
        val minX = allPoints.minOfOrNull { pt: PointF -> pt.x } ?: 0f
        val maxX = allPoints.maxOfOrNull { pt: PointF -> pt.x } ?: 0f
        val minY = allPoints.minOfOrNull { pt: PointF -> pt.y } ?: 0f
        val maxY = allPoints.maxOfOrNull { pt: PointF -> pt.y } ?: 0f
        return RectF(minX, minY, maxX, maxY)
    }

    fun dispose() {
        scope.cancel()
    }
}
