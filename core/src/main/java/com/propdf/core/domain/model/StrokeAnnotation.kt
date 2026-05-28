package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StrokeAnnotation(
    val pathData: List<PointF> = emptyList(),
    val color: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 4f,
    val tool: String = "pen"
) : Parcelable
