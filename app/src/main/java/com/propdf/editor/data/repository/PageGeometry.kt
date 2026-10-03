package com.propdf.editor.data.repository

import com.propdf.core.domain.model.ImageFitMode

/**
 * Pure (no PDFBox, no Android) geometry helpers for the Page Editor engine.
 *
 * "User space" = the coordinate system the page content stream is written in
 * (unrotated). "Display space" = what the reader sees after /Rotate is applied:
 * origin at the bottom-left of the VISIBLE box, x to the right, y up.
 *
 * Everything the engine draws (watermark, page numbers, images, headers) is
 * positioned in display space and mapped to user space with [toUserX]/[toUserY],
 * so rotated, cropped and non-origin-aligned pages are handled uniformly.
 *
 * Rotation is clockwise degrees as in the PDF /Rotate entry.
 */
internal class PageGeometry(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    rotationDegrees: Int
) {
    /** Normalised to 0, 90, 180 or 270. */
    val rotation: Int = Math.floorMod((rotationDegrees / 90) * 90, 360)

    val boxWidth: Float get() = right - left
    val boxHeight: Float get() = top - bottom

    private val quarterTurn: Boolean get() = rotation == 90 || rotation == 270

    val displayWidth: Float get() = if (quarterTurn) boxHeight else boxWidth
    val displayHeight: Float get() = if (quarterTurn) boxWidth else boxHeight

    /** Angle (CCW degrees, user space) of the display +x axis. */
    val displayAxisAngle: Float get() = rotation.toFloat()

    fun toUserX(dx: Float, dy: Float): Float = when (rotation) {
        0 -> left + dx
        90 -> left + (boxWidth - dy)
        180 -> left + (boxWidth - dx)
        else -> left + dy
    }

    fun toUserY(dx: Float, dy: Float): Float = when (rotation) {
        0 -> bottom + dy
        90 -> bottom + dx
        180 -> bottom + (boxHeight - dy)
        else -> bottom + (boxHeight - dx)
    }

    /** Text/graphics angle in user space that appears at [displayCcwDegrees] on screen. */
    fun userAngle(displayCcwDegrees: Float): Float = displayCcwDegrees + rotation

    data class Margins(val left: Float, val bottom: Float, val right: Float, val top: Float)

    /** Maps margins measured on the displayed page to user-space margins. */
    fun displayMarginsToUser(dl: Float, dt: Float, dr: Float, db: Float): Margins = when (rotation) {
        0 -> Margins(left = dl, bottom = db, right = dr, top = dt)
        90 -> Margins(left = dt, bottom = dl, right = db, top = dr)
        180 -> Margins(left = dr, bottom = dt, right = dl, top = db)
        else -> Margins(left = db, bottom = dr, right = dt, top = dl)
    }

    /** Result of [planResize]; all values are in user space. */
    data class ResizePlan(
        val scaleX: Float, val scaleY: Float,
        val newWidth: Float, val newHeight: Float,
        val translateX: Float, val translateY: Float
    )

    /**
     * Plans mapping this page's visible box onto a new box whose DISPLAYED size is
     * [targetW] x [targetH]. Content point (x,y) maps to
     * (x*scaleX + translateX, y*scaleY + translateY) inside a new box [0,0,newWidth,newHeight].
     */
    fun planResize(targetW: Float, targetH: Float, keepAspect: Boolean, scaleContent: Boolean): ResizePlan {
        val dw = displayWidth.coerceAtLeast(0.0001f)
        val dh = displayHeight.coerceAtLeast(0.0001f)
        val sxD: Float
        val syD: Float
        if (!scaleContent) {
            sxD = 1f; syD = 1f
        } else if (keepAspect) {
            val s = minOf(targetW / dw, targetH / dh); sxD = s; syD = s
        } else {
            sxD = targetW / dw; syD = targetH / dh
        }
        val sx = if (quarterTurn) syD else sxD
        val sy = if (quarterTurn) sxD else syD
        val newW = if (quarterTurn) targetH else targetW
        val newH = if (quarterTurn) targetW else targetH
        val offX = (newW - boxWidth * sx) / 2f
        val offY = (newH - boxHeight * sy) / 2f
        return ResizePlan(sx, sy, newW, newH, offX - left * sx, offY - bottom * sy)
    }
}

/** Image layout used by image-page insertion and insertion onto an existing page. */
internal object ImageLayout {
    fun fit(mode: ImageFitMode, imgW: Float, imgH: Float, availW: Float, availH: Float): Pair<Float, Float> {
        val aw = availW.coerceAtLeast(1f)
        val ah = availH.coerceAtLeast(1f)
        val w = imgW.coerceAtLeast(1f)
        val h = imgH.coerceAtLeast(1f)
        return when (mode) {
            ImageFitMode.FILL -> aw to ah
            ImageFitMode.FIT_WIDTH -> aw to (aw * h / w)
            ImageFitMode.FIT_HEIGHT -> (ah * w / h) to ah
            ImageFitMode.ORIGINAL -> w to h
            ImageFitMode.FIT_CENTER -> {
                val s = minOf(aw / w, ah / h)
                (w * s) to (h * s)
            }
        }
    }
}

/** Pure page-order planning. Page numbers are 1-based. */
internal object PageOrderPlanner {

    fun deleteOrder(pageCount: Int, delete: Collection<Int>): List<Int> {
        val d = delete.toSet()
        return (1..pageCount).filter { it !in d }
    }

    /** Each duplicated page is followed immediately by one copy. */
    fun duplicateOrder(pageCount: Int, duplicate: Collection<Int>): List<Int> {
        val d = duplicate.toSet()
        val out = ArrayList<Int>(pageCount + d.size)
        for (i in 1..pageCount) {
            out.add(i)
            if (i in d) out.add(i)
        }
        return out
    }

    /** [target] is an index into the REMAINING pages (0 = front), as the Page Editor has always used it. */
    fun moveOrder(pageCount: Int, pages: Collection<Int>, target: Int): List<Int> {
        val moving = pages.filter { it in 1..pageCount }.distinct()
        val remaining = (1..pageCount).filterNot { it in moving }
        val insertAt = target.coerceIn(0, remaining.size)
        return remaining.subList(0, insertAt) + moving + remaining.subList(insertAt, remaining.size)
    }

    fun extractOrder(pageCount: Int, pages: Collection<Int>): List<Int> =
        pages.filter { it in 1..pageCount }

    fun <T> insertAt(existing: List<T>, position1Based: Int, items: List<T>): List<T> {
        val at = position1Based.coerceIn(1, existing.size + 1) - 1
        return existing.subList(0, at) + items + existing.subList(at, existing.size)
    }

    /** True when every page 1..pageCount appears at least once (nothing is dropped). */
    fun coversAllPages(pageCount: Int, order: List<Int>): Boolean =
        order.toSet().containsAll((1..pageCount).toList())

    fun everyN(pageCount: Int, n: Int): List<IntRange> {
        val step = n.coerceAtLeast(1)
        val out = ArrayList<IntRange>()
        var start = 1
        while (start <= pageCount) {
            val end = (start + step - 1).coerceAtMost(pageCount)
            out.add(start..end)
            start = end + 1
        }
        return out
    }

    fun bySize(pageCount: Int, totalBytes: Long, maxBytes: Long): List<IntRange> {
        val avg = (totalBytes.coerceAtLeast(1L) / pageCount.coerceAtLeast(1)).coerceAtLeast(1L)
        val perChunk = (maxBytes / avg).toInt().coerceAtLeast(1)
        return everyN(pageCount, perChunk)
    }

    fun evenChunks(pageCount: Int, chunks: Int): List<IntRange> {
        val c = chunks.coerceAtLeast(1)
        val perChunk = ((pageCount + c - 1) / c).coerceAtLeast(1)
        return everyN(pageCount, perChunk)
    }
}
