package com.propdf.viewer.gesture
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
import com.propdf.viewer.annotation.touch.AnnotationTouchEngine
import com.propdf.viewer.coords.PdfCoordinateSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.math.hypot
/**
* Unified gesture coordinator that handles ALL touch input for a PDF page.
*
* ARCHITECTURE:
* - Single touch entry point (no more fighting between View and SurfaceView)
* - Mode-based dispatch: VIEWER_MODE vs ANNOTATION_MODE
* - Viewer gestures (pan, zoom, fling) handled when tool == NONE
* - Annotation gestures (draw, select, move) handled when tool != NONE
* - Coordinates converted once via PdfCoordinateSpace, then used everywhere
*
* ELIMINATES:
* - Gesture conflicts between overlay and page view
* - Double touch handling (both views consuming events)
* - Coordinate conversion mismatches (single conversion path)
* - Touch lag (no IPC between SurfaceView and View)
*/
class UnifiedGestureCoordinator(
context: Context,
private val annotationManager: AnnotationManager,
private val coordSpace: PdfCoordinateSpace,
private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
enum class InteractionMode {
VIEWER, // Pan, zoom, fling
ANNOTATION, // Draw, select, move
MOVE // Drag selected annotation
}
private var currentMode = InteractionMode.VIEWER
private var currentTool = AnnotationTouchEngine.ToolMode.NONE
// Viewer gesture detectors
private val gestureDetector = GestureDetector(context, ViewerGestureListener())
private val scaleDetector = ScaleGestureDetector(context, ViewerScaleListener())
// Annotation state
private val pressureBuffer = ArrayDeque<Float>(5)
private val pointBuffer = ArrayDeque<PointF>(8)
private var currentStroke: Stroke? = null
private var currentInkAnnotation: InkAnnotation? = null
private var currentShapeAnnotation: ShapeAnnotation? = null
private var currentSignatureAnnotation: SignatureAnnotation? = null
private var moveStartPoint: PointF? = null
private var moveStartAnnotation: Annotation? = null
// Callbacks
var onViewerPan: ((dx: Float, dy: Float) -> Unit)? = null
var onViewerZoom: ((scaleFactor: Float, focusX: Float, focusY: Float) -> Unit)? = null
var onViewerTap: ((x: Float, y: Float) -> Unit)? = null
var onViewerDoubleTap: ((x: Float, y: Float) -> Unit)? = null
var onAnnotationCreated: ((Annotation) -> Unit)? = null
var onAnnotationSelected: ((Annotation?) -> Unit)? = null
var onRequestInvalidate: (() -> Unit)? = null
var pageIndex: Int = 0
companion object {
private const val MOVE_THRESHOLD_DP = 8f
private const val PALM_REJECTION_PRESSURE = 0.15f
private const val SMOOTHING_FACTOR = 0.7f
}
// ==================== PUBLIC API ====================
fun setTool(tool: AnnotationTouchEngine.ToolMode) {
currentTool = tool
currentMode = if (tool == AnnotationTouchEngine.ToolMode.NONE) {
InteractionMode.VIEWER
} else {
InteractionMode.ANNOTATION
}
if (tool == AnnotationTouchEngine.ToolMode.SELECT || tool == AnnotationTouchEngine.ToolMode.NONE) {
annotationManager.deselectAll()
}
}
fun getTool(): AnnotationTouchEngine.ToolMode = currentTool
/**
* SINGLE entry point for ALL touch events.
* Returns true if consumed, false to pass to parent.
*/
fun onTouchEvent(event: MotionEvent): Boolean {
// Always feed to detectors for gesture recognition
val gestureHandled = gestureDetector.onTouchEvent(event)
val scaleHandled = scaleDetector.onTouchEvent(event)
val pdfPoint = coordSpace.screenToPdf(event.x, event.y)
val normalizedPoint = PointF(
pdfPoint.x / coordSpace.pageWidthPoints,
pdfPoint.y / coordSpace.pageHeightPoints
)
when (event.actionMasked) {
MotionEvent.ACTION_DOWN -> {
return handleActionDown(event, pdfPoint, normalizedPoint)
}
MotionEvent.ACTION_MOVE -> {
return handleActionMove(event, pdfPoint, normalizedPoint)
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
return handleActionUp(event, pdfPoint, normalizedPoint)
}
}
return gestureHandled || scaleHandled
}
// ==================== ACTION HANDLERS ====================
private fun handleActionDown(event: MotionEvent, pdfPoint: PointF, normalizedPoint: PointF): Boolean {
when (currentMode) {
InteractionMode.VIEWER -> {
// Check if tapping on annotation -> switch to MOVE mode
val hit = annotationManager.hitTest(pageIndex, normalizedPoint.x, normalizedPoint.y, tolerance = 0.02f)
if (hit != null) {
annotationManager.selectAnnotation(hit.id)
moveStartAnnotation = hit
moveStartPoint = normalizedPoint
currentMode = InteractionMode.MOVE
onAnnotationSelected?.invoke(hit)
onRequestInvalidate?.invoke()
return true
}
// Let viewer handle (pan start)
return false
}
InteractionMode.ANNOTATION -> {
return handleAnnotationDown(event, normalizedPoint)
}
InteractionMode.MOVE -> {
// Shouldn't happen, but handle gracefully
return true
}
}
}
private fun handleActionMove(event: MotionEvent, pdfPoint: PointF, normalizedPoint: PointF): Boolean {
when (currentMode) {
InteractionMode.MOVE -> {
val start = moveStartPoint ?: return false
val dx = normalizedPoint.x - start.x
val dy = normalizedPoint.y - start.y
val annotation = moveStartAnnotation ?: return false
val threshold = 0.005f
if (abs(dx) > threshold || abs(dy) > threshold) {
annotationManager.moveAnnotation(annotation.id, dx, dy)
moveStartPoint = normalizedPoint
onRequestInvalidate?.invoke()
}
return true
}
InteractionMode.ANNOTATION -> {
return handleAnnotationMove(event, normalizedPoint)
}
InteractionMode.VIEWER -> {
// Viewer pan is handled by ScaleGestureDetector and scroll logic
return false
}
}
}
private fun handleActionUp(event: MotionEvent, pdfPoint: PointF, normalizedPoint: PointF): Boolean {
when (currentMode) {
InteractionMode.MOVE -> {
moveStartPoint = null
moveStartAnnotation = null
currentMode = if (currentTool == AnnotationTouchEngine.ToolMode.NONE) {
InteractionMode.VIEWER
} else {
InteractionMode.ANNOTATION
}
}
InteractionMode.ANNOTATION -> {
finalizeDrawing()
pointBuffer.clear()
pressureBuffer.clear()
}
InteractionMode.VIEWER -> {
// Viewer up handled by detectors
}
}
return true
}
// ==================== ANNOTATION HANDLERS ====================
private fun handleAnnotationDown(event: MotionEvent, normalizedPoint: PointF): Boolean {
when (currentTool) {
AnnotationTouchEngine.ToolMode.SELECT -> {
val hit = annotationManager.hitTest(pageIndex, normalizedPoint.x, normalizedPoint.y, tolerance = 0.02f)
annotationManager.selectAnnotation(hit?.id)
onAnnotationSelected?.invoke(hit)
onRequestInvalidate?.invoke()
}
AnnotationTouchEngine.ToolMode.ERASER -> {
val hit = annotationManager.hitTest(pageIndex, normalizedPoint.x, normalizedPoint.y, tolerance = 0.03f)
hit?.let { annotationManager.removeAnnotation(it.id) }
onRequestInvalidate?.invoke()
}
AnnotationTouchEngine.ToolMode.TEXT_COMMENT -> {
val annotation = TextCommentAnnotation(
pageIndex = pageIndex,
anchorX = normalizedPoint.x * coordSpace.pageWidthPoints,
anchorY = normalizedPoint.y * coordSpace.pageHeightPoints,
color = Color.YELLOW
)
annotationManager.addAnnotation(annotation)
onAnnotationCreated?.invoke(annotation)
onRequestInvalidate?.invoke()
}
AnnotationTouchEngine.ToolMode.STICKY_NOTE -> {
val annotation = StickyNoteAnnotation(
pageIndex = pageIndex,
x = normalizedPoint.x,
y = normalizedPoint.y,
color = Color.parseColor("#FFF9C4")
)
annotationManager.addAnnotation(annotation)
onAnnotationCreated?.invoke(annotation)
onRequestInvalidate?.invoke()
}
else -> {
// Drawing tool - start new annotation
startDrawing(normalizedPoint, event.pressure)
}
}
return true
}
private fun handleAnnotationMove(event: MotionEvent, normalizedPoint: PointF): Boolean {
val pressure = smoothPressure(event.pressure)
if (pressure < PALM_REJECTION_PRESSURE) return true
val point = PointF(
normalizedPoint.x * coordSpace.pageWidthPoints,
normalizedPoint.y * coordSpace.pageHeightPoints
)
when (currentTool) {
AnnotationTouchEngine.ToolMode.PENCIL, AnnotationTouchEngine.ToolMode.FREEHAND -> {
val stroke = currentStroke ?: return false
val newStroke = stroke.addPoint(point, pressure)
currentStroke = newStroke
currentInkAnnotation?.let { ink ->
val updatedStrokes = ink.strokes.dropLast(1) + newStroke
val updated = ink.copy(strokes = updatedStrokes)
currentInkAnnotation = updated
annotationManager.updateAnnotation(updated)
onRequestInvalidate?.invoke()
}
}
AnnotationTouchEngine.ToolMode.ARROW, AnnotationTouchEngine.ToolMode.RECTANGLE,
AnnotationTouchEngine.ToolMode.CIRCLE -> {
val start = pointBuffer.firstOrNull() ?: return false
val startPdf = PointF(start.x * coordSpace.pageWidthPoints, start.y * coordSpace.pageHeightPoints)
val rectBounds = RectF(
minOf(startPdf.x, point.x),
minOf(startPdf.y, point.y),
maxOf(startPdf.x, point.x),
maxOf(startPdf.y, point.y)
)
currentShapeAnnotation?.let { shape ->
val updated = shape.copy(rectBounds = rectBounds)
currentShapeAnnotation = updated
annotationManager.updateAnnotation(updated)
onRequestInvalidate?.invoke()
}
}
AnnotationTouchEngine.ToolMode.SIGNATURE -> {
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
onRequestInvalidate?.invoke()
}
}
else -> {}
}
return true
}
private fun startDrawing(normalizedPoint: PointF, pressure: Float) {
pointBuffer.clear()
pressureBuffer.clear()
pointBuffer.addLast(normalizedPoint)
pressureBuffer.addLast(pressure)
val pdfPoint = PointF(
normalizedPoint.x * coordSpace.pageWidthPoints,
normalizedPoint.y * coordSpace.pageHeightPoints
)
when (currentTool) {
AnnotationTouchEngine.ToolMode.PENCIL, AnnotationTouchEngine.ToolMode.FREEHAND -> {
val stroke = Stroke(points = listOf(pdfPoint), pressures = listOf(pressure))
currentStroke = stroke
val annotation = InkAnnotation(
pageIndex = pageIndex,
type = if (currentTool == AnnotationTouchEngine.ToolMode.PENCIL) AnnotationType.PENCIL else AnnotationType.FREEHAND,
color = Color.RED,
strokeWidth = 3f,
strokes = listOf(stroke)
)
currentInkAnnotation = annotation
annotationManager.addAnnotation(annotation)
}
AnnotationTouchEngine.ToolMode.ARROW -> {
val annotation = ShapeAnnotation(
pageIndex = pageIndex,
type = AnnotationType.ARROW,
color = Color.RED,
strokeWidth = 3f,
rectBounds = RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y),
endArrow = true
)
currentShapeAnnotation = annotation
annotationManager.addAnnotation(annotation)
}
AnnotationTouchEngine.ToolMode.RECTANGLE -> {
val annotation = ShapeAnnotation(
pageIndex = pageIndex,
type = AnnotationType.RECTANGLE,
color = Color.RED,
strokeWidth = 3f,
rectBounds = RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y)
)
currentShapeAnnotation = annotation
annotationManager.addAnnotation(annotation)
}
AnnotationTouchEngine.ToolMode.CIRCLE -> {
val annotation = ShapeAnnotation(
pageIndex = pageIndex,
type = AnnotationType.CIRCLE,
color = Color.RED,
strokeWidth = 3f,
rectBounds = RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y)
)
currentShapeAnnotation = annotation
annotationManager.addAnnotation(annotation)
}
AnnotationTouchEngine.ToolMode.SIGNATURE -> {
val stroke = Stroke(points = listOf(pdfPoint), pressures = listOf(pressure))
currentStroke = stroke
val annotation = SignatureAnnotation(
pageIndex = pageIndex,
strokes = listOf(stroke),
rectBounds = RectF(pdfPoint.x, pdfPoint.y, pdfPoint.x, pdfPoint.y)
)
currentSignatureAnnotation = annotation
annotationManager.addAnnotation(annotation)
}
else -> {}
}
onRequestInvalidate?.invoke()
}
private fun finalizeDrawing() {
when (currentTool) {
AnnotationTouchEngine.ToolMode.PENCIL, AnnotationTouchEngine.ToolMode.FREEHAND -> {
currentInkAnnotation?.let { ink ->
if (ink.strokes.isEmpty() || ink.strokes.all { it.points.size < 2 }) {
annotationManager.removeAnnotation(ink.id)
} else {
onAnnotationCreated?.invoke(ink)
}
}
currentInkAnnotation = null
currentStroke = null
}
AnnotationTouchEngine.ToolMode.ARROW, AnnotationTouchEngine.ToolMode.RECTANGLE,
AnnotationTouchEngine.ToolMode.CIRCLE -> {
currentShapeAnnotation?.let { shape ->
if (shape.rectBounds.width() < 0.005f * coordSpace.pageWidthPoints ||
shape.rectBounds.height() < 0.005f * coordSpace.pageHeightPoints) {
annotationManager.removeAnnotation(shape.id)
} else {
onAnnotationCreated?.invoke(shape)
}
}
currentShapeAnnotation = null
}
AnnotationTouchEngine.ToolMode.SIGNATURE -> {
currentSignatureAnnotation?.let { sig ->
if (sig.strokes.isEmpty() || sig.strokes.all { it.points.size < 2 }) {
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
onRequestInvalidate?.invoke()
}
// ==================== PRESSURE SMOOTHING ====================
private fun smoothPressure(raw: Float): Float {
pressureBuffer.addLast(raw.coerceIn(0.1f, 1.0f))
if (pressureBuffer.size > 5) pressureBuffer.removeFirst()
val weights = listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.35f)
var sum = 0f
var weightSum = 0f
pressureBuffer.forEachIndexed { index, p ->
val w = weights.getOrElse(index) { 0.2f }
sum += p * w
weightSum += w
}
return if (weightSum > 0) sum / weightSum else raw
}
private fun calculateBounds(strokes: List<Stroke>): RectF {
if (strokes.isEmpty()) return RectF()
val allPoints = strokes.flatMap { it.points }
val minX = allPoints.minOfOrNull { it.x } ?: 0f
val maxX = allPoints.maxOfOrNull { it.x } ?: 0f
val minY = allPoints.minOfOrNull { it.y } ?: 0f
val maxY = allPoints.maxOfOrNull { it.y } ?: 0f
return RectF(minX, minY, maxX, maxY)
}
// ==================== VIEWER GESTURE LISTENERS ====================
private inner class ViewerGestureListener : GestureDetector.SimpleOnGestureListener() {
override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
if (currentMode == InteractionMode.VIEWER) {
onViewerTap?.invoke(e.x, e.y)
}
return true
}
override fun onDoubleTap(e: MotionEvent): Boolean {
if (currentMode == InteractionMode.VIEWER) {
onViewerDoubleTap?.invoke(e.x, e.y)
}
return true
}
override fun onScroll(
e1: MotionEvent?,
e2: MotionEvent,
distanceX: Float,
distanceY: Float
): Boolean {
if (currentMode == InteractionMode.VIEWER) {
onViewerPan?.invoke(-distanceX, -distanceY)
}
return true
}
override fun onFling(
e1: MotionEvent?,
e2: MotionEvent,
velocityX: Float,
velocityY: Float
): Boolean {
// Fling handled by SmoothScroller in PremiumPageView
return true
}
}
private inner class ViewerScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
override fun onScale(detector: ScaleGestureDetector): Boolean {
if (currentMode == InteractionMode.VIEWER) {
onViewerZoom?.invoke(detector.scaleFactor, detector.focusX, detector.focusY)
}
return true
}
}
fun dispose() {
scope.cancel()
}
}
