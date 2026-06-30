package com.propdf.annotations.smoothing

import com.propdf.annotations.model.PointF
import kotlin.math.*

/**
 * Cubic Bezier curve smoothing for handwriting strokes with pressure support.
 *
 * Algorithm:
 * 1. Collect raw input points with pressure data
 * 2. Calculate control points using Catmull-Rom spline logic
 * 3. Generate interpolated points along cubic Bezier curves
 * 4. Preserve pressure values through interpolation
 * 5. Douglas-Peucker simplification for storage optimization
 *
 * Produces natural, anti-aliased handwriting with reduced jitter
 * and realistic pressure variation.
 */
object BezierSmoother {

    private const val SMOOTHING_FACTOR = 0.2f // Tension: 0=sharp, 1=very smooth
    private const val INTERPOLATION_STEPS = 8 // Points per curve segment
    private const val MIN_POINT_DISTANCE = 2f // Minimum distance between points (pixels)

    /**
     * Smooth a raw point list into a dense, smooth curve.
     * Preserves pressure data through interpolation.
     */
    fun smooth(rawPoints: List<PointF>): List<PointF> {
        if (rawPoints.size < 3) return rawPoints

        // Remove duplicate/near-duplicate points
        val filtered = filterNearbyPoints(rawPoints)
        if (filtered.size < 3) return filtered

        val result = mutableListOf<PointF>()
        result.add(filtered[0]) // Start point

        for (i in 1 until filtered.size - 1) {
            val prev = filtered[i - 1]
            val curr = filtered[i]
            val next = filtered[i + 1]

            // Calculate control points
            val cp1 = calculateControlPoint(prev, curr, next, true)
            val cp2 = calculateControlPoint(prev, curr, next, false)

            // Generate interpolated points along Bezier curve
            for (t in 1..INTERPOLATION_STEPS) {
                val step = t / INTERPOLATION_STEPS.toFloat()
                val point = cubicBezier(prev, cp1, cp2, next, step)
                // Interpolate pressure
                val pressure = interpolatePressure(prev, next, step)
                result.add(point.copy(pressure = pressure))
            }
        }

        result.add(filtered.last()) // End point
        return result
    }

    /**
     * Smooth with velocity-based pressure for finger input.
     * Faster movement = lighter pressure.
     */
    fun smoothWithSimulatedPressure(rawPoints: List<PointF>): List<PointF> {
        if (rawPoints.size < 3) return rawPoints

        val filtered = filterNearbyPoints(rawPoints)
        if (filtered.size < 3) return filtered

        val result = mutableListOf<PointF>()
        result.add(filtered[0].copy(pressure = 0.8f))

        for (i in 1 until filtered.size - 1) {
            val prev = filtered[i - 1]
            val curr = filtered[i]
            val next = filtered[i + 1]

            // Calculate velocity for pressure simulation
            val velocity = curr.distanceTo(prev)
            val simulatedPressure = (1.0f - (velocity / 50f).coerceIn(0f, 0.5f))

            val cp1 = calculateControlPoint(prev, curr, next, true)
            val cp2 = calculateControlPoint(prev, curr, next, false)

            for (t in 1..INTERPOLATION_STEPS) {
                val step = t / INTERPOLATION_STEPS.toFloat()
                val point = cubicBezier(prev, cp1, cp2, next, step)
                val pressure = interpolatePressure(
                    prev.copy(pressure = if (i == 1) 0.8f else prev.pressure),
                    next.copy(pressure = simulatedPressure),
                    step
                )
                result.add(point.copy(pressure = pressure))
            }
        }

        result.add(filtered.last().copy(pressure = 0.6f))
        return result
    }

    /**
     * Calculate a control point for cubic Bezier using Catmull-Rom tension.
     */
    private fun calculateControlPoint(
        prev: PointF,
        curr: PointF,
        next: PointF,
        isFirst: Boolean
    ): PointF {
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val factor = if (isFirst) SMOOTHING_FACTOR else -SMOOTHING_FACTOR

        return PointF(
            curr.x + dx * factor,
            curr.y + dy * factor,
            curr.pressure
        )
    }

    /**
     * Cubic Bezier interpolation at parameter t.
     */
    private fun cubicBezier(
        p0: PointF,
        p1: PointF,
        p2: PointF,
        p3: PointF,
        t: Float
    ): PointF {
        val u = 1 - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t

        val x = uuu * p0.x + 3 * uu * t * p1.x + 3 * u * tt * p2.x + ttt * p3.x
        val y = uuu * p0.y + 3 * uu * t * p1.y + 3 * u * tt * p2.y + ttt * p3.y

        return PointF(x, y)
    }

    /**
     * Linear pressure interpolation between two points.
     */
    private fun interpolatePressure(start: PointF, end: PointF, t: Float): Float {
        return start.pressure + (end.pressure - start.pressure) * t
    }

    /**
     * Remove points that are too close together to reduce noise.
     */
    private fun filterNearbyPoints(points: List<PointF>): List<PointF> {
        if (points.isEmpty()) return points
        val result = mutableListOf(points[0])
        for (i in 1 until points.size) {
            if (points[i].distanceTo(result.last()) >= MIN_POINT_DISTANCE) {
                result.add(points[i])
            }
        }
        return result
    }

    /**
     * Douglas-Peucker simplification to reduce point count for storage.
     * Preserves shape while minimizing data.
     */
    fun simplify(points: List<PointF>, epsilon: Float = 2.0f): List<PointF> {
        if (points.size <= 2) return points

        val first = points.first()
        val last = points.last()
        var maxDist = 0f
        var maxIndex = 0

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], first, last)
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }

        return if (maxDist > epsilon) {
            val left = simplify(points.subList(0, maxIndex + 1), epsilon)
            val right = simplify(points.subList(maxIndex, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val mag = hypot(dx, dy)
        if (mag == 0f) return point.distanceTo(lineStart)

        val u = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / (mag * mag)
        val ix = lineStart.x + u * dx
        val iy = lineStart.y + u * dy
        return hypot(point.x - ix, point.y - iy)
    }
}
