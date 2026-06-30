package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import kotlin.math.cos
import kotlin.math.sin

/**
 * Text content annotations: free text, sticky notes, text boxes.
 * Supports rich text properties and bounded/unbounded modes.
 */
data class TextAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val textType: TextType,
    val text: String,
    val fontSize: Float = 14f,
    val fontFamily: String = "Helvetica",
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val textAlignment: TextAlignment = TextAlignment.LEFT,
    val rect: RectF,
    @ColorInt override val color: Int,
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val borderWidth: Float = 0f,
    val borderRadius: Float = 0f,
    val padding: Float = 4f,
    val isMultiline: Boolean = true,
    val iconName: String = "Note",
    val popupOpen: Boolean = false,
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

    enum class TextType { FREE_TEXT, STICKY_NOTE, TEXTBOX }
    enum class TextAlignment { LEFT, CENTER, RIGHT, JUSTIFY }

    override val type: AnnotationType = when (textType) {
        TextType.FREE_TEXT -> AnnotationType.FREE_TEXT
        TextType.STICKY_NOTE -> AnnotationType.STICKY_NOTE
        TextType.TEXTBOX -> AnnotationType.TEXTBOX
    }

    override fun getBounds(): RectF = RectF(rect)

    override fun hitTest(x: Float, y: Float, tolerance: Float): Boolean {
        val expanded = RectF(rect).apply { inset(-tolerance, -tolerance) }
        return expanded.contains(x, y)
    }

    override fun translate(dx: Float, dy: Float): Annotation = copy(
        rect = RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy),
        modifiedAt = currentTime()
    )

    override fun scale(factor: Float, pivotX: Float, pivotY: Float): Annotation = copy(
        rect = RectF(
            pivotX + (rect.left - pivotX) * factor,
            pivotY + (rect.top - pivotY) * factor,
            pivotX + (rect.right - pivotX) * factor,
            pivotY + (rect.bottom - pivotY) * factor
        ),
        fontSize = fontSize * factor,
        borderWidth = borderWidth * factor,
        padding = padding * factor,
        modifiedAt = currentTime()
    )

    override fun rotate(degrees: Float, pivotX: Float, pivotY: Float): Annotation {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val corners = listOf(
            PointF(rect.left, rect.top),
            PointF(rect.right, rect.top),
            PointF(rect.right, rect.bottom),
            PointF(rect.left, rect.bottom)
        )
        val rotated = corners.map { p ->
            val dx = p.x - pivotX
            val dy = p.y - pivotY
            PointF(pivotX + dx * c - dy * s, pivotY + dx * s + dy * c)
        }
        val left = rotated.minOf { it.x }
        val top = rotated.minOf { it.y }
        val right = rotated.maxOf { it.x }
        val bottom = rotated.maxOf { it.y }

        return copy(
            rect = RectF(left, top, right, bottom),
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
