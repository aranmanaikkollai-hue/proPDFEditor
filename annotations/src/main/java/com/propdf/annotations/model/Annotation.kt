package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import java.util.UUID

/**
 * Base annotation class with common properties.
 * All annotation types extend this sealed class.
 * 
 * Key design decisions:
 * - Immutable data classes for thread safety
 * - Copy-on-write for all mutations via history commands
 * - PDF coordinate system (page space, not screen space)
 * - UUID-based identification for cross-layer references
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
    open val isSelected: Boolean = false,
    open val author: String = "",
    open val subject: String = "",
    open val contents: String = ""  // PDF/Contents string
) {
    abstract val type: AnnotationType

    /**
     * Get bounding box in PDF page coordinates.
     */
    abstract fun getBounds(): RectF

    /**
     * Check if point hits this annotation (for selection).
     * @param tolerance Hit tolerance in PDF page coordinates
     */
    abstract fun hitTest(x: Float, y: Float, tolerance: Float = 10f): Boolean

    /**
     * Move annotation by delta in page coordinates.
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
     * Return a copy with new z-index.
     */
    abstract fun withZIndex(newZIndex: Int): Annotation

    /**
     * Return a copy with selection state.
     */
    abstract fun withSelected(selected: Boolean): Annotation

    /**
     * Check if this annotation intersects with a lasso polygon.
     */
    abstract fun intersectsLasso(polygon: List<PointF>): Boolean

    /**
     * Get the center point of this annotation.
     */
    open fun getCenter(): PointF {
        val bounds = getBounds()
        return PointF(bounds.centerX(), bounds.centerY())
    }

    /**
     * Clone with updated modification time.
     */
    protected fun currentTime(): Long = System.currentTimeMillis()
}
