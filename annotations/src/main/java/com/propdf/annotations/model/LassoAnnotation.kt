package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Lasso selection annotation - used for selection, not persisted.
 * Represents a freehand selection polygon drawn by the user.
 */
data class LassoAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val polygon: List<PointF>,
    @ColorInt override val color: Int = android.graphics.Color.parseColor("#2196F3"),
    override val opacity: Float = 0.2f,
    override val zIndex: Int = Int.MAX_VALUE, // Always on top
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false,
    override val author: String = "",
    override val subject: String = "",
    override val contents: String = ""
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected, author, subject, contents) {

    override val type: AnnotationType = AnnotationType.LASSO_SELECT

    override fun getBounds(): RectF {
        if (polygon.isEmpty()) return RectF()
        val left = polygon.minOf { it.x }
        val top = polygon.minOf { it.y }
        val right = polygon.maxOf { it.x }
        val bottom = polygon.maxOf { it.y }
        return RectF(left, top, right, bottom)
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        return isPointInPolygon(PointF(x, y), polygon)
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        polygon = polygon.map { PointF(it.x + dx, it.y + dy, it.pressure) },
        modifiedAt = currentTime()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        polygon = polygon.map {
            PointF(
                pivotX + (it.x - pivotX) * factor,
                pivotY + (it.y - pivotY) * factor,
                it.pressure
            )
        },
        modifiedAt = currentTime()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        return copy(
            polygon = polygon.map {
                val dx = it.x - pivotX
                val dy = it.y - pivotY
                PointF(pivotX + dx * c - dy * s, pivotY + dx * s + dy * c, it.pressure)
            },
            modifiedAt = currentTime()
        )
    }

    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

    override fun withSelected(selected: Boolean): Annotation = copy(isSelected = selected)

    override fun intersectsLasso(otherPolygon: List<PointF>): Boolean {
        // Check if any point of this lasso is inside the other polygon
        return polygon.any { isPointInPolygon(it, otherPolygon) }
    }

    /**
     * Check if this lasso contains another annotation's bounds.
     */
    fun containsAnnotation(annotation: Annotation): Boolean {
        val bounds = annotation.getBounds()
        val corners = listOf(
            PointF(bounds.left, bounds.top),
            PointF(bounds.right, bounds.top),
            PointF(bounds.right, bounds.bottom),
            PointF(bounds.left, bounds.bottom),
            annotation.getCenter()
        )
        return corners.any { isPointInPolygon(it, polygon) }
    }

    /**
     * Close the polygon by adding the first point at the end.
     */
    fun closePolygon(): LassoAnnotation {
        return if (polygon.isNotEmpty() && polygon.first() != polygon.last()) {
            copy(polygon = polygon + polygon.first())
        } else {
            this
        }
    }

    private fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
