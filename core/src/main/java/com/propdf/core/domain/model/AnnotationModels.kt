package com.propdf.core.domain.model

import android.graphics.Color
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AnnotationStroke(
    val pathData: List<PointF>,
    val color: Int = Color.RED,
    val strokeWidth: Float = 4f,
    val tool: String = "freehand",
    val stampText: String = ""
) : Parcelable

@Parcelize
data class AnnotationText(
    val x: Float,
    val y: Float,
    val text: String,
    val color: Int = Color.BLACK,
    val sizePx: Float = 44f
) : Parcelable

@Parcelize
data class PageAnnotations(
    val pageIndex: Int,
    val strokes: List<AnnotationStroke> = emptyList(),
    val textAnnots: List<AnnotationText> = emptyList()
) : Parcelable
