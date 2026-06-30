package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt

/**
 * Text markup annotations: highlight, underline, strikeout, squiggly.
 * Supports multi-line text selection with multiple rects.
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
    override val isSelected: Boolean = false,
    override val author: String = "",
    override val subject: String = "",
    override val contents: String = ""
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected, author, subject, contents) {

    enum class HighlightType { HIGHLIGHT, UNDERLINE, STRIKEOUT, SQUIGGLY }

    override val type: AnnotationType = when (highlightType) {
        HighlightType.HIGHLIGHT -> AnnotationType.HIGHLIGHT
        HighlightType.UNDERLINE -> AnnotationType.UNDERLINE
        HighlightType.STRIKEOUT -> AnnotationType.STRIKEOUT
        HighlightType.SQUIGGLY -> AnnotationType.SQUIGGLY
    }

    override fun getBounds(): RectF {
        if (rects.isEmpty()) return RectF()
        val result = RectF(rects[0])
        rects.drop(1).forEach { result.union(it) }
        return result
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        return rects.any { 
            RectF(it).apply { inset(-tolerance, -tolerance) }.contains(x, y) 
        }
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        rects = rects.map { RectF(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) },
        modifiedAt = currentTime()
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
        modifiedAt = currentTime()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        return copy(
            rects = rects.map { rect ->
                val corners = listOf(
                    PointF(rect.left, rect.top),
                    PointF(rect.right, rect.top),
                    PointF(rect.right, rect.bottom),
                    PointF(rect.left, rect.bottom)
                )
                val rotated = corners.map { p ->
                    val dx = p.x - pivotX
                    val dy = p.y - pivotY
                    PointF(pivotX + dx * cos - dy * sin, pivotY + dx * sin + dy * cos)
                }
                val left = rotated.minOf { it.x }
                val top = rotated.minOf { it.y }
                val right = rotated.maxOf { it.x }
                val bottom = rotated.maxOf { it.y }
                RectF(left, top, right, bottom)
            },
            modifiedAt = currentTime()
        )
    }

    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

    override fun withSelected(selected: Boolean): Annotation = copy(isSelected = selected)

    override fun intersectsLasso(polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        // Check if any rect center is inside polygon
        return rects.any { rect ->
            val center = PointF(rect.centerX(), rect.centerY())
            isPointInPolygon(center, polygon)
        }
    }

    private fun isPointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
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
