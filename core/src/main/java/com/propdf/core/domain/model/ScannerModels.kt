package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class ScanMode {
    BATCH, ID_CARD, BOOK, BUSINESS_CARD, SPLICE
}

enum class ColorMode {
    AUTO, COLOR, GRAY, BW
}

@Parcelize
data class ScannedPage(
    val index: Int,
    val width: Int,
    val height: Int,
    val rotation: Int = 0
) : Parcelable

@Parcelize
data class ExportConfig(
    val format: ExportFormat,
    val quality: Int = 80,
    val pageWidthPt: Float = 0f,
    val pageHeightPt: Float = 0f
) : Parcelable

enum class ExportFormat { PDF, JPEG, PDF_AND_JPEG }
