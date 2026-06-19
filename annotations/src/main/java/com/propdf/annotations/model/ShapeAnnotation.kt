package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Rectangle, circle, line, arrow annotations.
 */
data class ShapeAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val shapeType: ShapeType,
    val rect: RectF,
    val strokeWidth: Float = 2f,
    val fillColor: Int? = null,
    @ColorInt override val color: Int,
    override val opacity: Float = 1.0f,
    override val zIndex: Int = 0,
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected) {

    override val type: AnnotationType = when (shapeType) {
        ShapeType.RECTANGLE -> AnnotationType.RECTANGLE
        ShapeType.CIRCLE -> AnnotationType.CIRCLE
        ShapeType.LINE -> AnnotationType.LINE
        ShapeType.ARROW -> AnnotationType.ARROW
    }

    enum class ShapeType { RECTANGLE, CIRCLE, LINE, ARROW }

    override fun getBounds(): RectF = RectF(rect)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(rect).apply { inset(-tolerance, -tolerance) }
        return expanded.contains(x, y)
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        rect = RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy),
        modifiedAt = System.currentTimeMillis()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rect = RectF(
            pivotX + (rect.left - pivotX) * factor,
            pivotY + (rect.top - pivotY) * factor,
            pivotX + (rect.right - pivotX) * factor,
            pivotY + (rect.bottom - pivotY) * factor
        ),
        strokeWidth = strokeWidth * factor,
        modifiedAt = System.currentTimeMillis()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        // For shapes, rotation is complex; simplified: just update modifiedAt
        return copy(modifiedAt = System.currentTimeMillis())
    }
    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

}
