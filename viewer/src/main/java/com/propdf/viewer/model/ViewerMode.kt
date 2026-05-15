package com.propdf.viewer.model

import android.graphics.Bitmap

enum class ViewMode {
    SINGLE_PAGE_VERTICAL,
    SINGLE_PAGE_HORIZONTAL,
    CONTINUOUS_VERTICAL,
    CONTINUOUS_HORIZONTAL,
    TWO_PAGE_SPREAD,
    READING_MODE
}

enum class ViewerTheme {
    LIGHT,
    DARK,
    NIGHT,
    SEPIA,
    HIGH_CONTRAST
}

enum class ZoomMode {
    FIT_WIDTH,
    FIT_PAGE,
    ACTUAL_SIZE,
    CUSTOM
}

data class PdfTab(
    val id: String,
    val documentUri: String,
    val documentName: String,
    val currentPage: Int = 0,
    val zoomLevel: Float = 1.0f,
    val scrollPosition: Int = 0,
    val isPasswordProtected: Boolean = false
)

data class PageThumbnail(
    val pageIndex: Int,
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val aspectRatio: Float = 1.414f
)

enum class ScrollDirection {
    NONE, VERTICAL, HORIZONTAL, BOTH
}

enum class RenderPriority {
    LOW, NORMAL, HIGH, CRITICAL
}
