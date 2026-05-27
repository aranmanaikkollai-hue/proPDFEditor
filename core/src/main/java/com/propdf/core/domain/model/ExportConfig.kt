package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ExportConfig(
    val format: ExportFormat = ExportFormat.PDF,
    val pageWidthPt: Float = 0f,
    val pageHeightPt: Float = 0f,
    val quality: Int = 90
) : Parcelable

enum class ExportFormat {
    PDF, JPEG, PNG
}
