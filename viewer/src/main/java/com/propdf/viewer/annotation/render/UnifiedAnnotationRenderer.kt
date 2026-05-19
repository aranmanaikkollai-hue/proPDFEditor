package com.propdf.viewer.annotation.render
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.propdf.viewer.annotation.model.Annotation
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import com.propdf.viewer.annotation.model.TextMarkupAnnotation
import com.propdf.viewer.coords.PdfCoordinateSpace
import kotlin.math.max
/**
* Unified annotation renderer that draws directly onto the PDF page canvas.
* Uses shared PdfCoordinateSpace for perfect synchronization with PDF transforms.
*
* ELIMINATES:
* - SurfaceView flickering (single canvas draw)
* - Overlay desync (same matrix as PDF)
* - Double buffering issues (no separate surface)
* - Gesture conflicts (single touch handler)
*/
class UnifiedAnnotationRenderer {
private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val path = Path()
// Dirty region tracking - only redraw changed areas
private var dirtyRect: RectF? = null
private var fullRedraw = true
/**
* Render annotations onto the same canvas as the PDF.
* Called from PremiumPageView.onDraw() AFTER PDF bitmap is drawn.
*
* @param canvas The PDF page canvas (already transformed by PdfCoordinateSpace)
* @param annotations List of annotations for this page
* @param coordSpace Shared coordinate space for conversion
* @param selectedId Currently selected annotation ID
* @param pageWidthPoints PDF page width in points
* @param pageHeightPoints PDF page height in points
*/
fun render(
canvas: Canvas,
annotations: List<Annotation>,
coordSpace: PdfCoordinateSpace,
selectedId: String? = null,
pageWidthPoints: Float = 612f,
pageHeightPoints: Float = 792f
) {
// Sort by z-index for proper layering
val sorted = annotations.sortedBy { it.zIndex }
sorted.forEach { annotation ->
canvas.save()
try {
when (annotation) {
is TextMarkupAnnotation -> renderTextMarkup(canvas, annotation, coordSpace)
is InkAnnotation -> renderInk(canvas, annotation, coordSpace)
is TextCommentAnnotation -> renderTextComment(canvas, annotation, coordSpace)
is StickyNoteAnnotation -> renderStickyNote(canvas, annotation, coordSpace)
is ShapeAnnotation -> renderShape(canvas, annotation, coordSpace)
is SignatureAnnotation -> renderSignature(canvas, annotation, coordSpace)
is ImageStampAnnotation -> renderImageStamp(canvas, annotation, coordSpace)
}
if (annotation.id == selectedId) {
renderSelectionHandles(canvas, annotation, coordSpace)
}
} finally {
canvas.restore()
}
}
fullRedraw = false
}
/** Mark full redraw needed (e.g., after zoom/pan) */
fun markFullRedraw() { fullRedraw = true }
/** Check if full redraw is needed */
fun needsFullRedraw(): Boolean = fullRedraw
// ==================== TEXT MARKUP ====================
private fun renderTextMarkup(
canvas: Canvas,
annotation: TextMarkupAnnotation,
coordSpace: PdfCoordinateSpace
) {
paint.apply {
color = annotation.color
alpha = (annotation.alpha * 255).toInt()
style = Paint.Style.FILL
}
val rects = if (annotation.quads.isNotEmpty()) annotation.quads else listOf(annotation.rectBounds)
rects.forEach { rect ->
val screenRect = coordSpace.pdfRectToScreen(rect)
when (annotation.type) {
AnnotationType.HIGHLIGHT -> {
canvas.drawRect(screenRect, paint)
}
AnnotationType.UNDERLINE -> {
paint.style = Paint.Style.STROKE
paint.strokeWidth = max(2f, screenRect.height() * 0.08f)
canvas.drawLine(
screenRect.left, screenRect.bottom,
screenRect.right, screenRect.bottom, paint
)
}
AnnotationType.STRIKEOUT -> {
paint.style = Paint.Style.STROKE
paint.strokeWidth = max(2f, screenRect.height() * 0.08f)
val midY = (screenRect.top + screenRect.bottom) / 2
canvas.drawLine(
screenRect.left, midY,
screenRect.right, midY, paint
)
}
else -> {}
}
}
}
// ==================== INK / FREEHAND ====================
private fun renderInk(
canvas: Canvas,
annotation: InkAnnotation,
coordSpace: PdfCoordinateSpace
) {
paint.apply {
color = annotation.color
alpha = 255
style = Paint.Style.STROKE
strokeCap = Paint.Cap.ROUND
strokeJoin = Paint.Join.ROUND
}
annotation.strokes.forEach { stroke ->
if (stroke.points.size < 2) return@forEach
path.reset()
val first = stroke.points.first()
val firstScreen = coordSpace.pdfToScreen(first.x, first.y)
path.moveTo(firstScreen.x, firstScreen.y)
for (i in 1 until stroke.points.size) {
val prev = stroke.points[i - 1]
val curr = stroke.points[i]
val prevScreen = coordSpace.pdfToScreen(prev.x, prev.y)
val currScreen = coordSpace.pdfToScreen(curr.x, curr.y)
val midX = (prevScreen.x + currScreen.x) / 2
val midY = (prevScreen.y + currScreen.y) / 2
path.quadTo(prevScreen.x, prevScreen.y, midX, midY)
}
val avgPressure = stroke.pressures.average().toFloat().coerceIn(0.3f, 1.0f)
val baseWidth = annotation.strokeWidth * coordSpace.getScale()
paint.strokeWidth = baseWidth * avgPressure
canvas.drawPath(path, paint)
}
}
// ==================== TEXT COMMENT ====================
private fun renderTextComment(
canvas: Canvas,
annotation: TextCommentAnnotation,
coordSpace: PdfCoordinateSpace
) {
val screenPos = coordSpace.pdfToScreen(annotation.anchorX, annotation.anchorY)
val size = 24f * coordSpace.getScale()
paint.apply {
color = annotation.color
alpha = 255
style = Paint.Style.FILL
}
canvas.drawCircle(screenPos.x, screenPos.y, size, paint)
textPaint.apply {
color = Color.WHITE
textSize = size * 1.2f
typeface = Typeface.DEFAULT_BOLD
textAlign = Paint.Align.CENTER
}
canvas.drawText("N", screenPos.x, screenPos.y + size * 0.4f, textPaint)
}
// ==================== STICKY NOTE ====================
private fun renderStickyNote(
canvas: Canvas,
annotation: StickyNoteAnnotation,
coordSpace: PdfCoordinateSpace
) {
val topLeft = coordSpace.pdfToScreen(annotation.x, annotation.y)
val bottomRight = coordSpace.pdfToScreen(
annotation.x + annotation.width,
annotation.y + annotation.height
)
val left = topLeft.x
val top = topLeft.y
val right = bottomRight.x
val bottom = bottomRight.y
// Shadow
paint.apply {
color = Color.BLACK
alpha = 40
style = Paint.Style.FILL
}
canvas.drawRect(left + 4, top + 4, right + 4, bottom + 4, paint)
// Background
paint.apply {
color = annotation.color
alpha = 255
}
canvas.drawRect(left, top, right, bottom, paint)
// Folded corner
val foldSize = (right - left) * 0.15f
path.reset()
path.moveTo(right - foldSize, top)
path.lineTo(right, top + foldSize)
path.lineTo(right, top)
path.close()
paint.color = Color.parseColor("#E0E0E0")
canvas.drawPath(path, paint)
// Border
paint.apply {
style = Paint.Style.STROKE
strokeWidth = 1f
color = Color.parseColor("#CCCCCC")
}
canvas.drawRect(left, top, right, bottom, paint)
// Text
if (annotation.text.isNotBlank()) {
textPaint.apply {
color = Color.BLACK
textSize = (right - left) * 0.12f
typeface = Typeface.DEFAULT
}
val maxWidth = right - left - 16
val lines = breakTextIntoLines(annotation.text, textPaint, maxWidth)
var y = top + textPaint.textSize * 1.5f
lines.forEach { line ->
if (y < bottom - textPaint.textSize) {
canvas.drawText(line, left + 8, y, textPaint)
y += textPaint.textSize * 1.3f
}
}
}
}
// ==================== SHAPES ====================
private fun renderShape(
canvas: Canvas,
annotation: ShapeAnnotation,
coordSpace: PdfCoordinateSpace
) {
val screenRect = coordSpace.pdfRectToScreen(annotation.rectBounds)
val left = screenRect.left
val top = screenRect.top
val right = screenRect.right
val bottom = screenRect.bottom
paint.apply {
color = annotation.color
alpha = 255
style = Paint.Style.STROKE
strokeWidth = annotation.strokeWidth * coordSpace.getScale()
}
annotation.fillColor?.let { fill ->
paint.style = Paint.Style.FILL
paint.color = fill
paint.alpha = (annotation.fillAlpha * 255).toInt()
drawShapeBody(canvas, annotation.type, left, top, right, bottom)
paint.style = Paint.Style.STROKE
paint.color = annotation.color
paint.alpha = 255
}
when (annotation.type) {
AnnotationType.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
AnnotationType.CIRCLE -> canvas.drawOval(left, top, right, bottom, paint)
AnnotationType.ARROW -> renderArrow(canvas, left, top, right, bottom, annotation.startArrow, annotation.endArrow)
else -> {}
}
}
private fun drawShapeBody(canvas: Canvas, type: AnnotationType, left: Float, top: Float, right: Float, bottom: Float) {
when (type) {
AnnotationType.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
AnnotationType.CIRCLE -> canvas.drawOval(left, top, right, bottom, paint)
else -> {}
}
}
private fun renderArrow(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, startArrow: Boolean, endArrow: Boolean) {
val startX = if (startArrow) left + (right - left) * 0.1f else left
val endX = if (endArrow) right - (right - left) * 0.1f else right
val midY = (top + bottom) / 2
canvas.drawLine(startX, midY, endX, midY, paint)
if (endArrow) drawArrowHead(canvas, endX, midY, -1f, 0f, paint.strokeWidth * 3)
if (startArrow) drawArrowHead(canvas, startX, midY, 1f, 0f, paint.strokeWidth * 3)
}
private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, dirX: Float, dirY: Float, size: Float) {
path.reset()
path.moveTo(x, y)
path.lineTo(x + dirX * size + size * 0.5f, y - size * 0.5f)
path.lineTo(x + dirX * size + size * 0.5f, y + size * 0.5f)
path.close()
paint.style = Paint.Style.FILL
canvas.drawPath(path, paint)
paint.style = Paint.Style.STROKE
}
// ==================== SIGNATURE ====================
private fun renderSignature(
canvas: Canvas,
annotation: SignatureAnnotation,
coordSpace: PdfCoordinateSpace
) {
paint.apply {
color = annotation.color
alpha = 255
style = Paint.Style.STROKE
strokeCap = Paint.Cap.ROUND
strokeJoin = Paint.Join.ROUND
strokeWidth = annotation.strokeWidth * coordSpace.getScale()
}
annotation.strokes.forEach { stroke ->
if (stroke.points.size < 2) return@forEach
path.reset()
val first = stroke.points.first()
val firstScreen = coordSpace.pdfToScreen(first.x, first.y)
path.moveTo(firstScreen.x, firstScreen.y)
for (i in 1 until stroke.points.size) {
val prev = stroke.points[i - 1]
val curr = stroke.points[i]
val prevScreen = coordSpace.pdfToScreen(prev.x, prev.y)
val currScreen = coordSpace.pdfToScreen(curr.x, curr.y)
val midX = (prevScreen.x + currScreen.x) / 2
val midY = (prevScreen.y + currScreen.y) / 2
path.quadTo(prevScreen.x, prevScreen.y, midX, midY)
}
canvas.drawPath(path, paint)
}
}
// ==================== IMAGE STAMP ====================
private fun renderImageStamp(
canvas: Canvas,
annotation: ImageStampAnnotation,
coordSpace: PdfCoordinateSpace
) {
val topLeft = coordSpace.pdfToScreen(annotation.x, annotation.y)
val bottomRight = coordSpace.pdfToScreen(
annotation.x + annotation.width,
annotation.y + annotation.height
)
paint.apply {
color = Color.parseColor("#CCCCCC")
alpha = 100
style = Paint.Style.FILL
}
canvas.drawRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, paint)
paint.apply {
color = Color.parseColor("#999999")
alpha = 255
style = Paint.Style.STROKE
strokeWidth = 2f
}
canvas.drawRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, paint)
textPaint.apply {
color = Color.parseColor("#666666")
textSize = (bottomRight.x - topLeft.x) * 0.1f
textAlign = Paint.Align.CENTER
}
canvas.drawText("[Image]", (topLeft.x + bottomRight.x) / 2, (topLeft.y + bottomRight.y) / 2, textPaint)
}
// ==================== SELECTION HANDLES ====================
private fun renderSelectionHandles(
canvas: Canvas,
annotation: Annotation,
coordSpace: PdfCoordinateSpace
) {
val bounds = annotation.getBounds()
val screenRect = coordSpace.pdfRectToScreen(bounds)
val handleSize = 8f * coordSpace.getScale()
paint.apply {
color = Color.parseColor("#448AFF")
alpha = 255
style = Paint.Style.FILL
}
val corners = listOf(
screenRect.left to screenRect.top,
screenRect.right to screenRect.top,
screenRect.left to screenRect.bottom,
screenRect.right to screenRect.bottom
)
corners.forEach { (x, y) ->
canvas.drawCircle(x, y, handleSize, paint)
}
paint.style = Paint.Style.STROKE
paint.strokeWidth = 2f
canvas.drawRect(screenRect, paint)
}
// ==================== UTILITIES ====================
private fun breakTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
val lines = mutableListOf<String>()
var remaining = text
while (remaining.isNotEmpty()) {
var breakIndex = remaining.length
while (breakIndex > 0 && paint.measureText(remaining.substring(0, breakIndex)) > maxWidth) {
breakIndex--
}
if (breakIndex == 0) breakIndex = 1
lines.add(remaining.substring(0, breakIndex))
remaining = remaining.substring(breakIndex).trimStart()
}
return lines
}
}
