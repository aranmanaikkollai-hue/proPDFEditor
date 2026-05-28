package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TextAnnotation(
    val text: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val color: Int = 0xFF000000.toInt(),
    val sizePx: Float = 48f
) : Parcelable
