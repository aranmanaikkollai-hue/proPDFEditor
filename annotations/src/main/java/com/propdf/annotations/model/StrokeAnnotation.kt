package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import com.propdf.annotations.smoothing.BezierSmoother

/**
 * Freehand ink annotation with smoothed stroke points.
 */
data class StrokeAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val points: List<PointF>,
    val strokeWidth: Float = 3f,
    val isSmooth: Boolean = true,
    @ColorInt override val color: Int,
    override val opacity: Float = 1.0f,
    override val zIndex: Int = 0,
    override val createdAt: Long = System.currentTimeMillis(),
    override val modifiedAt: Long = System.currentTimeMillis(),
    override val isVisible: Boolean = true,
    override val isSelected: Boolean = false
) : Annotation(id, pageIndex, zIndex, color, opacity, createdAt, modifiedAt, isVisible, isSelected) {

    override val type: AnnotationType = AnnotationType.INK

    private val smoothedPoints: List<PointF> by lazy {
        if (isSmooth && points.size > 2) {
            BezierSmoother.smooth(points)
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

        val padding = strokeWidth
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val testPoint = PointF(x, y)
        return smoothedPoints.any { it.distanceTo(testPoint) <= tolerance + strokeWidth }
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        points = points.map { PointF(it.x + dx, it.y + dy) },
        modifiedAt = System.currentTimeMillis()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        points = points.map {
            PointF(
                pivotX + (it.x - pivotX) * factor,
                pivotY + (it.y - pivotY) * factor
            )
        },
        strokeWidth = strokeWidth * factor,
        modifiedAt = System.currentTimeMillis()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()

        return copy(
            points = points.map {
                val dx = it.x - pivotX
                val dy = it.y - pivotY
                PointF(
                    pivotX + dx * cos - dy * sin,
                    pivotY + dx * sin + dy * cos
                )
            },
            modifiedAt = System.currentTimeMillis()
        )
    }
}
