package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PointF(
    val x: Float = 0f,
    val y: Float = 0f
) : Parcelable
