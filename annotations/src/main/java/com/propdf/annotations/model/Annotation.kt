package com.propdf.annotations.model

import androidx.annotation.ColorInt
import java.util.UUID

/**
 * Base annotation class with common properties.
 * All annotation types extend this.
 */
sealed class Annotation(
    open val id: String = UUID.randomUUID().toString(),
    open val pageIndex: Int,
    open val zIndex: Int = 0,
    @ColorInt open val color: Int,
    open val opacity: Float = 1.0f,
    open val createdAt: Long = System.currentTimeMillis(),
    open val modifiedAt: Long = System.currentTimeMillis(),
    open val isVisible: Boolean = true,
    open val isSelected: Boolean = false
) {
    abstract val type: AnnotationType

    /**
     * Get bounding box in PDF page coordinates.
     */
    abstract fun getBounds(): android.graphics.RectF

    /**
     * Check if point hits this annotation (for selection).
     */
    abstract fun hitTest(x: Float, y: Float, tolerance: Float = 10f): Boolean

    /**
     * Move annotation by delta.
     */
    abstract fun translate(dx: Float, dy: Float): Annotation

    /**
     * Scale annotation by factor from pivot point.
     */
    abstract fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation

    /**
     * Rotate annotation by degrees around pivot.
     */
    abstract fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation

    /**
     * Return a copy of this annotation with a new z-index.
     */
    abstract fun withZIndex(newZIndex: Int): Annotation
}
