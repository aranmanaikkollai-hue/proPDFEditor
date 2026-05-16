package com.propdf.viewer.annotation.model

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import java.util.UUID

sealed class Annotation(
    open val id: String = UUID.randomUUID().toString(),
    open val pageIndex: Int,
    open val type: AnnotationType,
    open val color: Int = Color.YELLOW,
    open val alpha: Float = 0.5f,
    open val strokeWidth: Float = 3f,
    open val state: AnnotationState = AnnotationState.CREATED,
    open val createdAt: Long = System.currentTimeMillis(),
    open val zIndex: Int = 0
) {
    abstract fun getBounds(): RectF
    abstract fun hitTest(x: Float, y: Float, tolerance: Float = 0.01f): Boolean
    abstract fun move(dx: Float, dy: Float): Annotation
    abstract fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation
    abstract fun withState(newState: AnnotationState): Annotation
}

data class TextMarkupAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType,
    override val color: Int = Color.YELLOW,
    override val alpha: Float = 0.3f,
    override val strokeWidth: Float = 2f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val rectBounds: RectF,
    val quads: List<RectF> = emptyList()
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(rectBounds)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(rectBounds).apply { inset(-tolerance, -tolerance) }
        return expanded.contains(x, y)
    }

    override fun move(dx: Float, dy: Float): Annotation = copy(
        rectBounds = RectF(rectBounds).apply { offset(dx, dy) },
        quads = quads.map { quad -> RectF(quad).apply { offset(dx, dy) } }
    )

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rectBounds = scaleRect(rectBounds, scaleFactor, pivotX, pivotY),
        quads = quads.map { quad -> scaleRect(quad, scaleFactor, pivotX, pivotY) }
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

data class InkAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType = AnnotationType.FREEHAND,
    override val color: Int = Color.RED,
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 3f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val strokes: List<Stroke> = emptyList()
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF {
        if (strokes.isEmpty()) return RectF()
        val allPoints = strokes.flatMap { stroke: Stroke -> stroke.points }
        val minX = allPoints.minOfOrNull { pt: PointF -> pt.x } ?: 0f
        val maxX = allPoints.maxOfOrNull { pt: PointF -> pt.x } ?: 0f
        val minY = allPoints.minOfOrNull { pt: PointF -> pt.y } ?: 0f
        val maxY = allPoints.maxOfOrNull { pt: PointF -> pt.y } ?: 0f
        return RectF(minX, minY, maxX, maxY)
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        return strokes.any { stroke: Stroke ->
            stroke.points.any { pt: PointF ->
                val dx = pt.x - x
                val dy = pt.y - y
                (dx * dx + dy * dy) <= tolerance * tolerance
            }
        }
    }

    override fun move(dx: Float, dy: Float): Annotation = copy(
        strokes = strokes.map { stroke: Stroke ->
            stroke.copy(points = stroke.points.map { pt: PointF -> PointF(pt.x + dx, pt.y + dy) })
        }
    )

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        strokes = strokes.map { stroke: Stroke ->
            stroke.copy(points = stroke.points.map { pt: PointF ->
                PointF(pivotX + (pt.x - pivotX) * scaleFactor, pivotY + (pt.y - pivotY) * scaleFactor)
            })
        }
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
    fun addStroke(stroke: Stroke): InkAnnotation = copy(strokes = strokes + stroke)
}

data class Stroke(
    val points: List<PointF> = emptyList(),
    val pressures: List<Float> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun addPoint(point: PointF, pressure: Float = 1.0f): Stroke = copy(
        points = points + point,
        pressures = pressures + pressure.coerceIn(0.1f, 1.0f)
    )
}

data class TextCommentAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType = AnnotationType.TEXT_COMMENT,
    override val color: Int = Color.YELLOW,
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 1f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val anchorX: Float,
    val anchorY: Float,
    val text: String = "",
    val author: String = "",
    val icon: String = "Note"
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(anchorX - 0.02f, anchorY - 0.02f, anchorX + 0.02f, anchorY + 0.02f)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val dx = x - anchorX
        val dy = y - anchorY
        return (dx * dx + dy * dy) <= tolerance * tolerance
    }

    override fun move(dx: Float, dy: Float): Annotation = copy(anchorX = anchorX + dx, anchorY = anchorY + dy)

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        anchorX = pivotX + (anchorX - pivotX) * scaleFactor,
        anchorY = pivotY + (anchorY - pivotY) * scaleFactor
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

data class StickyNoteAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType = AnnotationType.STICKY_NOTE,
    override val color: Int = Color.parseColor("#FFF9C4"),
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 1f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val x: Float,
    val y: Float,
    val width: Float = 0.15f,
    val height: Float = 0.15f,
    val text: String = "",
    val author: String = ""
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(x, y, x + width, y + height)
    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean = getBounds().contains(x, y)
    override fun move(dx: Float, dy: Float): Annotation = copy(x = x + dx, y = y + dy)

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        x = pivotX + (x - pivotX) * scaleFactor,
        y = pivotY + (y - pivotY) * scaleFactor,
        width = width * scaleFactor,
        height = height * scaleFactor
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

data class ShapeAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType,
    override val color: Int = Color.RED,
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 3f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val rectBounds: RectF,
    val fillColor: Int? = null,
    val fillAlpha: Float = 0.2f,
    val startArrow: Boolean = false,
    val endArrow: Boolean = false
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(rectBounds)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(rectBounds).apply { inset(-tolerance, -tolerance) }
        return expanded.contains(x, y)
    }

    override fun move(dx: Float, dy: Float): Annotation = copy(rectBounds = RectF(rectBounds).apply { offset(dx, dy) })

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rectBounds = scaleRect(rectBounds, scaleFactor, pivotX, pivotY)
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

data class SignatureAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType = AnnotationType.SIGNATURE,
    override val color: Int = Color.BLACK,
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 2f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val strokes: List<Stroke> = emptyList(),
    val rectBounds: RectF = RectF(),
    val signerName: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(rectBounds)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(rectBounds).apply { inset(-tolerance, -tolerance) }
        return expanded.contains(x, y)
    }

    override fun move(dx: Float, dy: Float): Annotation = copy(
        rectBounds = RectF(rectBounds).apply { offset(dx, dy) },
        strokes = strokes.map { stroke: Stroke ->
            stroke.copy(points = stroke.points.map { pt: PointF -> PointF(pt.x + dx, pt.y + dy) })
        }
    )

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rectBounds = scaleRect(rectBounds, scaleFactor, pivotX, pivotY),
        strokes = strokes.map { stroke: Stroke ->
            stroke.copy(points = stroke.points.map { pt: PointF ->
                PointF(pivotX + (pt.x - pivotX) * scaleFactor, pivotY + (pt.y - pivotY) * scaleFactor)
            })
        }
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

data class ImageStampAnnotation(
    override val id: String = UUID.randomUUID().toString(),
    override val pageIndex: Int,
    override val type: AnnotationType = AnnotationType.IMAGE_STAMP,
    override val color: Int = Color.TRANSPARENT,
    override val alpha: Float = 1.0f,
    override val strokeWidth: Float = 0f,
    override val state: AnnotationState = AnnotationState.CREATED,
    override val createdAt: Long = System.currentTimeMillis(),
    override val zIndex: Int = 0,
    val x: Float,
    val y: Float,
    val width: Float = 0.2f,
    val height: Float = 0.2f,
    val imageUri: String = "",
    val rotation: Float = 0f
) : Annotation(id, pageIndex, type, color, alpha, strokeWidth, state, createdAt, zIndex) {

    override fun getBounds(): RectF = RectF(x, y, x + width, y + height)
    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean = getBounds().contains(x, y)
    override fun move(dx: Float, dy: Float): Annotation = copy(x = x + dx, y = y + dy)

    override fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        x = pivotX + (x - pivotX) * scaleFactor,
        y = pivotY + (y - pivotY) * scaleFactor,
        width = width * scaleFactor,
        height = height * scaleFactor
    )

    override fun withState(newState: AnnotationState): Annotation = copy(state = newState)
}

private fun scaleRect(rect: RectF, scale: Float, pivotX: Float, pivotY: Float): RectF = RectF(
    pivotX + (rect.left - pivotX) * scale,
    pivotY + (rect.top - pivotY) * scale,
    pivotX + (rect.right - pivotX) * scale,
    pivotY + (rect.bottom - pivotY) * scale
)
