package com.propdf.annotations.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Serializable point for annotation geometry with optional pressure data.
 * Pressure is normalized 0.0-1.0 for stylus/touch pressure simulation.
 */
@Parcelize
data class PointF(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,  // Normalized pressure 0.0-1.0
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {

    companion object {
        fun fromAndroidPoint(point: android.graphics.PointF, pressure: Float = 1.0f): PointF =
            PointF(point.x, point.y, pressure)

        fun fromMotionEvent(event: android.view.MotionEvent, pointerIndex: Int = 0): PointF {
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            val pressure = if (event.device?.supportsSource(android.view.InputDevice.SOURCE_STYLUS) == true) {
                event.getPressure(pointerIndex).coerceIn(0.1f, 1.0f)
            } else {
                // Simulate pressure based on velocity for finger input
                0.7f
            }
            return PointF(x, y, pressure)
        }
    }

    fun toAndroidPoint(): android.graphics.PointF =
        android.graphics.PointF(x, y)

    fun distanceTo(other: PointF): Float {
        return kotlin.math.hypot(x - other.x, y - other.y)
    }

    fun midpoint(other: PointF): PointF {
        return PointF(
            (x + other.x) / 2f,
            (y + other.y) / 2f,
            (pressure + other.pressure) / 2f
        )
    }
}
