package com.propdf.pdfedit.convert

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel size for rendering a page, and whether it had to be reduced from what was asked. */
class RenderSize(val widthPx: Int, val heightPx: Int, val effectiveDpi: Double, val reduced: Boolean)

/** Computes render sizes. Pure arithmetic, so it is unit tested on the JVM. */
object RenderSizing {
    const val MIN_DPI = 36
    const val MAX_DPI = 600
    const val MAX_SIDE_PX = 16384

    /**
     * Size of a page of [widthPts] x [heightPts] points (1/72 inch) at [dpi], lowered when it would exceed
     * [maxPixels] in total or [MAX_SIDE_PX] on a side. The aspect ratio is kept.
     */
    fun compute(widthPts: Double, heightPts: Double, dpi: Int, maxPixels: Long): RenderSize {
        val w0 = if (widthPts > 0.0) widthPts else 1.0
        val h0 = if (heightPts > 0.0) heightPts else 1.0
        var scale = dpi.coerceIn(MIN_DPI, MAX_DPI) / 72.0
        val wanted = scale
        var w = max(1, (w0 * scale).roundToInt())
        var h = max(1, (h0 * scale).roundToInt())
        val limit = max(1L, maxPixels)
        var reduce = 1.0
        if (w.toLong() * h.toLong() > limit) reduce = minOf(reduce, sqrt(limit.toDouble() / (w.toDouble() * h.toDouble())))
        val longest = max(w, h)
        if (longest > MAX_SIDE_PX) reduce = minOf(reduce, MAX_SIDE_PX.toDouble() / longest)
        if (reduce < 1.0) {
            scale *= reduce
            w = max(1, floor(w0 * scale).toInt())
            h = max(1, floor(h0 * scale).toInt())
        }
        return RenderSize(w, h, scale * 72.0, scale < wanted - 1e-9)
    }
}
