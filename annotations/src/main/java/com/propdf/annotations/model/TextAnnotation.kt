package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Free text annotation.
 */
data class TextAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val text: String,
    val fontSize: Float = 14f,
    val rect: RectF,
    @ColorInt override val color: Int,
    val backgroundColor: Int? = null,
    override val opacity: Float = 1.0f,
    override val zIndex: Int = 0,
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected) {

    override val type: AnnotationType = AnnotationType.TEXT

    override fun getBounds(): RectF = RectF(rect)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        return rect.contains(x, y)
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
        fontSize = fontSize * factor,
        modifiedAt = System.currentTimeMillis()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        return copy(modifiedAt = System.currentTimeMillis())
    }
    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

}
