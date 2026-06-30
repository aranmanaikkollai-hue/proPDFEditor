package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shape annotations: rectangle, circle, line, arrow, polygon, cloud.
 * Supports fill color, stroke width, and dashed patterns.
 */
data class ShapeAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val shapeType: ShapeType,
    val rect: RectF,
    val strokeWidth: Float = 2f,
    val fillColor: Int? = null,
    val isDashed: Boolean = false,
    val dashPattern: FloatArray = floatArrayOf(5f, 5f),
    val startArrow: Boolean = false,
    val endArrow: Boolean = false,
    // For polygon/cloud
    val vertices: List<PointF> = emptyList(),
    @ColorInt override val color: Int,
    override val opacity: Float = 1.0f,
    override val zIndex: Int = 0,
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false,
    override val author: String = "",
    override val subject: String = "",
    override val contents: String = ""
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected, author, subject, contents) {

    enum class ShapeType { RECTANGLE, CIRCLE, LINE, ARROW, POLYGON, CLOUD }

    override val type: AnnotationType = when (shapeType) {
        ShapeType.RECTANGLE -> AnnotationType.RECTANGLE
        ShapeType.CIRCLE -> AnnotationType.CIRCLE
        ShapeType.LINE -> AnnotationType.LINE
        ShapeType.ARROW -> AnnotationType.ARROW
        ShapeType.POLYGON -> AnnotationType.POLYGON
        ShapeType.CLOUD -> AnnotationType.CLOUD
    }

    override fun getBounds(): RectF = when (shapeType) {
        ShapeType.POLYGON, ShapeType.CLOUD -> {
            if (vertices.isEmpty()) RectF(rect)
            else {
                val left = vertices.minOf { it.x }
                val top = vertices.minOf { it.y }
                val right = vertices.maxOf { it.x }
                val bottom = vertices.maxOf { it.y }
                RectF(left, top, right, bottom)
            }
        }
        else -> RectF(rect)
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(getBounds()).apply { inset(-tolerance, -tolerance) }
        if (!expanded.contains(x, y)) return false

        return when (shapeType) {
            ShapeType.RECTANGLE, ShapeType.CIRCLE -> true
            ShapeType.LINE, ShapeType.ARROW -> {
                // Distance from point to line segment
                val dist = distanceToLineSegment(x, y, rect.left, rect.top, rect.right, rect.bottom)
                dist <= tolerance + strokeWidth
            }
            ShapeType.POLYGON, ShapeType.CLOUD -> {
                isPointInPolygon(PointF(x, y), vertices)
            }
        }
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        rect = RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy),
        vertices = vertices.map { PointF(it.x + dx, it.y + dy, it.pressure) },
        modifiedAt = currentTime()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rect = RectF(
            pivotX + (rect.left - pivotX) * factor,
            pivotY + (rect.top - pivotY) * factor,
            pivotX + (rect.right - pivotX) * factor,
            pivotY + (rect.bottom - pivotY) * factor
        ),
        vertices = vertices.map {
            PointF(
                pivotX + (it.x - pivotX) * factor,
                pivotY + (it.y - pivotY) * factor,
                it.pressure
            )
        },
        strokeWidth = strokeWidth * factor,
        modifiedAt = currentTime()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        fun rotatePoint(p: PointF): PointF {
            val dx = p.x - pivotX
            val dy = p.y - pivotY
            return PointF(pivotX + dx * c - dy * s, pivotY + dx * s + dy * c, p.pressure)
        }

        val newRect = if (shapeType == ShapeType.RECTANGLE || shapeType == ShapeType.CIRCLE) {
            val corners = listOf(
                PointF(rect.left, rect.top),
                PointF(rect.right, rect.top),
                PointF(rect.right, rect.bottom),
                PointF(rect.left, rect.bottom)
            )
            val rotated = corners.map(::rotatePoint)
            val left = rotated.minOf { it.x }
            val top = rotated.minOf { it.y }
            val right = rotated.maxOf { it.x }
            val bottom = rotated.maxOf { it.y }
            RectF(left, top, right, bottom)
        } else {
            rect
        }

        return copy(
            rect = newRect,
            vertices = vertices.map(::rotatePoint),
            modifiedAt = currentTime()
        )
    }

    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

    override fun withSelected(selected: Boolean): Annotation = copy(isSelected = selected)

    override fun intersectsLasso(polygon: List<PointF>): Boolean {
        if (polygon.size < 3) return false
        val center = getCenter()
        return isPointInPolygon(center, polygon)
    }

    private fun distanceToLineSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1))
        var t = ((px - x1) * dx + (py - y1) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
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

    /**
     * Generate cloud vertices from a bounding rect.
     * Used when creating a cloud annotation from a drag gesture.
     */
    fun generateCloudVertices(): List<PointF> {
        val bounds = getBounds()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val rx = bounds.width() / 2f
        val ry = bounds.height() / 2f
        val segments = 16
        val result = mutableListOf<PointF>()
        for (i in 0 until segments) {
            val angle = 2 * Math.PI * i / segments
            // Add cloud-like bulges
            val bulge = if (i % 2 == 0) 1.15f else 0.85f
            val x = cx + rx * bulge * cos(angle).toFloat()
            val y = cy + ry * bulge * sin(angle).toFloat()
            result.add(PointF(x, y))
        }
        return result
    }
}
