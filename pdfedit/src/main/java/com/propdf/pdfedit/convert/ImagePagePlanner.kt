package com.propdf.pdfedit.convert

import com.propdf.pdfedit.geometry.RectPds
import kotlin.math.max
import kotlin.math.min

enum class PageMode {
    /** ISO A4 (595.28 x 841.89 points); landscape for a wide picture. */
    A4,

    /** US Letter (612 x 792 points); landscape for a wide picture. */
    LETTER,

    /** The page is the size of the picture at a stated resolution, with no margin. */
    MATCH_IMAGE
}

/** Page size in points and where the picture goes on it (rect origin bottom-left, y up). */
class PagePlan(val pageWidth: Double, val pageHeight: Double, val rect: RectPds)

/** Decides page size and picture placement for images to PDF. Pure arithmetic, unit tested on the JVM. */
object ImagePagePlanner {
    const val A4_WIDTH = 595.2756
    const val A4_HEIGHT = 841.8898
    const val LETTER_WIDTH = 612.0
    const val LETTER_HEIGHT = 792.0
    const val MAX_PAGE_POINTS = 14400.0

    /**
     * [displayWidth] x [displayHeight] is the upright picture size in pixels. For A4 and LETTER the picture is scaled
     * to fit inside the page minus [marginPts] on every side (never distorted) and centred; the page is landscape when
     * the picture is wider than tall. For MATCH_IMAGE the page is the picture at [matchDpi] and the margin is ignored.
     */
    fun plan(mode: PageMode, displayWidth: Int, displayHeight: Int, marginPts: Double, matchDpi: Int): PagePlan {
        val iw = max(1, displayWidth).toDouble()
        val ih = max(1, displayHeight).toDouble()
        if (mode == PageMode.MATCH_IMAGE) {
            val dpi = matchDpi.coerceIn(36, 600).toDouble()
            var pw = iw * 72.0 / dpi
            var ph = ih * 72.0 / dpi
            val biggest = max(pw, ph)
            if (biggest > MAX_PAGE_POINTS) {
                val f = MAX_PAGE_POINTS / biggest
                pw *= f
                ph *= f
            }
            pw = max(1.0, pw)
            ph = max(1.0, ph)
            return PagePlan(pw, ph, RectPds(0.0, 0.0, pw, ph))
        }
        val baseW = if (mode == PageMode.A4) A4_WIDTH else LETTER_WIDTH
        val baseH = if (mode == PageMode.A4) A4_HEIGHT else LETTER_HEIGHT
        val landscape = iw > ih
        val pw = if (landscape) baseH else baseW
        val ph = if (landscape) baseW else baseH
        val m = marginPts.coerceIn(0.0, min(pw, ph) / 4.0)
        val boxW = pw - 2 * m
        val boxH = ph - 2 * m
        val scale = min(boxW / iw, boxH / ih)
        val w = iw * scale
        val h = ih * scale
        val left = (pw - w) / 2.0
        val bottom = (ph - h) / 2.0
        return PagePlan(pw, ph, RectPds(left, bottom, left + w, bottom + h))
    }
}
