package com.propdf.pdfedit.convert

import com.propdf.pdfedit.geometry.Matrix2D
import com.propdf.pdfedit.geometry.RectPds

/**
 * EXIF orientation values 1-8 (JPEG photos from phones often carry 6 or 8) and how to draw a stored image upright.
 * Pure arithmetic, unit tested on the JVM.
 *
 * Meaning of the value: where the stored image's top-left corner appears when the photo is shown upright, and whether it
 * is mirrored. 1 normal, 2 mirrored left-right, 3 turned 180, 4 mirrored top-bottom, 5 transposed, 6 turned 90 clockwise,
 * 7 transversed, 8 turned 90 counter-clockwise.
 */
object ExifOrientation {

    fun normalize(value: Int): Int = if (value in 1..8) value else 1

    /** Size of the upright picture: width and height swap for 5-8. */
    fun displaySize(orientation: Int, width: Int, height: Int): Pair<Int, Int> =
        if (normalize(orientation) in 5..8) Pair(height, width) else Pair(width, height)

    /**
     * Matrix (PDF convention) that maps the stored image's unit square (u right, v up, as in a PDF image) onto [rect], so the
     * picture shows upright and fills [rect]. [rect] is the size of the upright picture.
     */
    fun matrixFor(orientation: Int, rect: RectPds): Matrix2D {
        // Upright unit coordinates X,Y (origin bottom-left) as a function of the stored unit coordinates u,v:
        //   X = xu*u + xv*v + x0     Y = yu*u + yv*v + y0
        val t: DoubleArray = when (normalize(orientation)) {
            2 -> doubleArrayOf(-1.0, 0.0, 1.0, 0.0, 1.0, 0.0)
            3 -> doubleArrayOf(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0)
            4 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 1.0)
            5 -> doubleArrayOf(0.0, -1.0, 1.0, -1.0, 0.0, 1.0)
            6 -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 1.0)
            7 -> doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            8 -> doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }
        // t = [xu, xv, x0, yu, yv, y0]
        val xu = t[0]; val xv = t[1]; val x0 = t[2]
        val yu = t[3]; val yv = t[4]; val y0 = t[5]
        val w = rect.width
        val h = rect.height
        return Matrix2D(w * xu, h * yu, w * xv, h * yv, rect.left + w * x0, rect.bottom + h * y0)
    }
}
