package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import com.propdf.annotations.smoothing.BezierSmoother
import kotlin.math.cos
import kotlin.math.sin

/**
 * Freehand ink annotation with pressure-sensitive stroke points.
 * Supports multiple pen types: ink, signature, calligraphy, marker, pencil, eraser.
 */
data class StrokeAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val points: List<PointF>,
    val strokeWidth: Float = 3f,
    val penType: PenType = PenType.INK,
    val isSmooth: Boolean = true,
    val simplifyEpsilon: Float = 1.5f,
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

    enum class PenType {
        INK,        // Standard ballpoint pen
        SIGNATURE,  // Signature ink (thicker, anti-aliased)
        CALLIGRAPHY, // Variable width based on angle
        MARKER,     // Highlighter-like broad stroke
        PENCIL,     // Sketchy, textured stroke
        ERASER      // Removes underlying ink (special handling)
    }

    override val type: AnnotationType = when (penType) {
        PenType.INK -> AnnotationType.INK
        PenType.SIGNATURE -> AnnotationType.SIGNATURE
        PenType.CALLIGRAPHY -> AnnotationType.CALLIGRAPHY
        PenType.MARKER -> AnnotationType.MARKER
        PenType.PENCIL -> AnnotationType.PENCIL
        PenType.ERASER -> AnnotationType.ERASER
    }

    /**
     * Cached smoothed points for rendering.
     * Lazily computed to avoid recomputation on every frame.
     */
    private val smoothedPoints: List<PointF> by lazy {
        if (isSmooth && points.size > 2) {
            BezierSmoother.smooth(points)
        } else {
            points
        }
    }

    /**
     * Simplified points for storage (reduces DB size).
     */
    val storagePoints: List<PointF> by lazy {
        if (points.size > 50) {
            BezierSmoother.simplify(points, simplifyEpsilon)
        } else {
            points
        }
    }

    fun getRenderPoints(): List<PointF> = smoothedPoints

    override fun getBounds(): RectF {
        if (points.isEmpty()) return RectF()
        var left = points[0].x
        var top = points[0].y
        var right = points[0].x
        var bottom = points[0].y

        points.forEach { p ->
            left = minOf(left, p.x)
            top = minOf(top, p.y)
            right = maxOf(right, p.x)
            bottom = maxOf(bottom, p.y)
        }

        val padding = when (penType) {
            PenType.MARKER -> strokeWidth * 2f
            PenType.CALLIGRAPHY -> strokeWidth * 1.5f
            else -> strokeWidth
        }
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        if (points.isEmpty()) return false
        val testPoint = PointF(x, y)
        val effectiveTolerance = tolerance + strokeWidth

        // Check distance to each line segment
        for (i in 0 until smoothedPoints.size - 1) {
            val p1 = smoothedPoints[i]
            val p2 = smoothedPoints[i + 1]
            if (distanceToSegment(testPoint, p1, p2) <= effectiveTolerance) {
                return true
            }
        }
        return false
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        points = points.map { PointF(it.x + dx, it.y + dy, it.pressure, it.timestamp) },
        modifiedAt = currentTime()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        points = points.map {
            PointF(
                pivotX + (it.x - pivotX) * factor,
                pivotY + (it.y - pivotY) * factor,
                it.pressure,
                it.timestamp
            )
        },
        strokeWidth = strokeWidth * factor,
        modifiedAt = currentTime()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        return copy(
            points = points.map {
                val dx = it.x - pivotX
                val dy = it.y - pivotY
                PointF(
                    pivotX + dx * c - dy * s,
                    pivotY + dx * s + dy * c,
                    it.pressure,
                    it.timestamp
                )
            },
            modifiedAt = currentTime()
        )
    }

    override fun withZIndex(newZIndex: Int): Annotation = copy(zIndex = newZIndex)

    override fun withSelected(selected: Boolean): Annotation = copy(isSelected = selected)

    override fun intersectsLasso(polygon: List<PointF>): Boolean {
        if (polygon.size < 3 || points.isEmpty()) return false
        // Check if any point is inside polygon OR if polygon contains center
        val center = getCenter()
        if (isPointInPolygon(center, polygon)) return true
        return points.any { isPointInPolygon(it, polygon) }
    }

    /**
     * Get variable stroke width at a specific point index based on pen type.
     */
    fun getStrokeWidthAt(index: Int): Float {
        val baseWidth = strokeWidth
        return when (penType) {
            PenType.INK -> baseWidth * points.getOrElse(index) { points.last() }.pressure
            PenType.SIGNATURE -> baseWidth * 1.2f * points.getOrElse(index) { points.last() }.pressure
            PenType.CALLIGRAPHY -> {
                // Simulate calligraphy nib: wider when moving horizontally
                if (index > 0) {
                    val dx = kotlin.math.abs(points[index].x - points[index - 1].x)
                    val dy = kotlin.math.abs(points[index].y - points[index - 1].y)
                    val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                    val widthFactor = kotlin.math.sin(2 * angle).toFloat()
                    baseWidth * (0.5f + 0.5f * kotlin.math.abs(widthFactor))
                } else baseWidth
            }
            PenType.MARKER -> baseWidth * 2.5f
            PenType.PENCIL -> baseWidth * (0.7f + 0.3f * kotlin.math.random().toFloat())
            PenType.ERASER -> baseWidth * 3f
        }
    }

    private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return p.distanceTo(a)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return kotlin.math.hypot(p.x - projX, p.y - projY)
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
