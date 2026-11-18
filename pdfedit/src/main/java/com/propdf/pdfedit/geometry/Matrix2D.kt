package com.propdf.pdfedit.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A point in Page Display Space (see [PageGeometry]): points, origin bottom-left of the displayed page, y up. */
data class PointPds(val x: Double, val y: Double)

/** An axis-aligned rectangle in Page Display Space. Build it with [of] to get left <= right and bottom <= top. */
data class RectPds(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom

    companion object {
        fun of(x1: Double, y1: Double, x2: Double, y2: Double) =
            RectPds(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

/**
 * 2D affine matrix in the PDF convention: a point is a row vector and (x, y) maps to
 * (x*a + y*c + e, x*b + y*d + f). [then] applies this matrix first and the other one second, which is the order in
 * which PDF concatenates matrices (the cm operator pre-multiplies the current transformation matrix).
 */
data class Matrix2D(
    val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double
) {
    fun transform(p: PointPds) = PointPds(p.x * a + p.y * c + e, p.x * b + p.y * d + f)

    fun then(o: Matrix2D) = Matrix2D(
        a * o.a + b * o.c,
        a * o.b + b * o.d,
        c * o.a + d * o.c,
        c * o.b + d * o.d,
        e * o.a + f * o.c + o.e,
        e * o.b + f * o.d + o.f
    )

    /** The inverse, or null when the matrix is (nearly) singular. */
    fun inverse(): Matrix2D? {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return null
        return Matrix2D(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    /** Bounding box of the four corners of [r] after this matrix. */
    fun transformBounds(r: RectPds): RectPds {
        val pts = listOf(
            transform(PointPds(r.left, r.bottom)), transform(PointPds(r.right, r.bottom)),
            transform(PointPds(r.right, r.top)), transform(PointPds(r.left, r.top))
        )
        return RectPds.of(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    companion object {
        val IDENTITY = Matrix2D(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun scale(sx: Double, sy: Double) = Matrix2D(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Maps the unit square (0,0)-(1,1) onto [r]; the placement matrix of an image drawn into [r]. */
        fun unitSquareTo(r: RectPds) = Matrix2D(r.width, 0.0, 0.0, r.height, r.left, r.bottom)
    }
}
