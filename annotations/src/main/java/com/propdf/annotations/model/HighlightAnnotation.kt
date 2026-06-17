package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Text highlight, underline, strikeout annotation.
 */
data class HighlightAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val highlightType: HighlightType,
    val rects: List<RectF>, // Multiple rects for multi-line text
    @ColorInt override val color: Int,
    override val opacity: Float = 0.3f,
    override val zIndex: Int = 0,
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected) {

    override val type: AnnotationType = when (highlightType) {
        HighlightType.HIGHLIGHT -> AnnotationType.HIGHLIGHT
        HighlightType.UNDERLINE -> AnnotationType.UNDERLINE
        HighlightType.STRIKEOUT -> AnnotationType.STRIKEOUT
    }

    enum class HighlightType { HIGHLIGHT, UNDERLINE, STRIKEOUT }

    override fun getBounds(): RectF {
        if (rects.isEmpty()) return RectF()
        val result = RectF(rects[0])
        rects.drop(1).forEach { result.union(it) }
        return result
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        return rects.any { it.contains(x, y) }
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        rects = rects.map { RectF(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) },
        modifiedAt = System.currentTimeMillis()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rects = rects.map {
            RectF(
                pivotX + (it.left - pivotX) * factor,
                pivotY + (it.top - pivotY) * factor,
                pivotX + (it.right - pivotX) * factor,
                pivotY + (it.bottom - pivotY) * factor
            )
        },
        modifiedAt = System.currentTimeMillis()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        return copy(modifiedAt = System.currentTimeMillis())
    }
}
