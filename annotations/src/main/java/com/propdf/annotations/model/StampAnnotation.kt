package com.propdf.annotations.model

import android.graphics.RectF
import androidx.annotation.ColorInt
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stamp annotations: predefined stamps, custom stamps, date stamps.
 * Supports image-based and text-based stamps with rotation.
 */
data class StampAnnotation(
    override val id: String = java.util.UUID.randomUUID().toString(),
    override val pageIndex: Int,
    val stampType: StampType,
    val stampName: String = "",
    val customText: String = "",
    val imagePath: String? = null, // For custom image stamps
    val rect: RectF,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    @ColorInt override val color: Int = android.graphics.Color.BLACK,
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val borderWidth: Float = 0f,
    val fontSize: Float = 24f,
    val fontFamily: String = "Helvetica-Bold",
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

    enum class StampType {
        APPROVED, REJECTED, DRAFT, FINAL, COMPLETED,
        CONFIDENTIAL, VOID, FOR_REVIEW, CUSTOM_TEXT,
        CUSTOM_IMAGE, DATE_STAMP
    }

    override val type: AnnotationType = when (stampType) {
        StampType.DATE_STAMP -> AnnotationType.DATE_STAMP
        else -> AnnotationType.STAMP
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
        scaleX = scaleX * factor,
        scaleY = scaleY * factor,
        fontSize = fontSize * factor,
        borderWidth = borderWidth * factor,
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
            rotation = (rotation + degrees) % 360f,
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

    /**
     * Get display text for the stamp.
     */
    fun getDisplayText(): String {
        return when (stampType) {
            StampType.APPROVED -> "APPROVED"
            StampType.REJECTED -> "REJECTED"
            StampType.DRAFT -> "DRAFT"
            StampType.FINAL -> "FINAL"
            StampType.COMPLETED -> "COMPLETED"
            StampType.CONFIDENTIAL -> "CONFIDENTIAL"
            StampType.VOID -> "VOID"
            StampType.FOR_REVIEW -> "FOR REVIEW"
            StampType.CUSTOM_TEXT -> customText
            StampType.CUSTOM_IMAGE -> stampName
            StampType.DATE_STAMP -> {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                sdf.format(java.util.Date())
            }
        }
    }

    /**
     * Get default color for predefined stamps.
     */
    fun getDefaultColor(): Int {
        return when (stampType) {
            StampType.APPROVED -> android.graphics.Color.parseColor("#4CAF50")
            StampType.REJECTED -> android.graphics.Color.parseColor("#F44336")
            StampType.DRAFT -> android.graphics.Color.parseColor("#FF9800")
            StampType.FINAL -> android.graphics.Color.parseColor("#2196F3")
            StampType.COMPLETED -> android.graphics.Color.parseColor("#9C27B0")
            StampType.CONFIDENTIAL -> android.graphics.Color.parseColor("#E91E63")
            StampType.VOID -> android.graphics.Color.parseColor("#795548")
            StampType.FOR_REVIEW -> android.graphics.Color.parseColor("#00BCD4")
            StampType.DATE_STAMP -> android.graphics.Color.parseColor("#3F51B5")
            else -> android.graphics.Color.BLACK
        }
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
