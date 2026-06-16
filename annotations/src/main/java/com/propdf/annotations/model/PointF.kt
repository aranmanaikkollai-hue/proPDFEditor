// annotations/src/main/java/com/propdf/annotations/model/PointF.kt
package com.propdf.annotations.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Serializable point for annotation geometry.
 */
@Parcelize
data class PointF(
    val x: Float,
    val y: Float
) : Parcelable {
    companion object {
        fun fromAndroidPoint(point: android.graphics.PointF): PointF =
            PointF(point.x, point.y)
    }

    fun toAndroidPoint(): android.graphics.PointF =
        android.graphics.PointF(x, y)

    fun distanceTo(other: PointF): Float {
        return kotlin.math.hypot(x - other.x, y - other.y)
    }
}
