package com.propdf.viewer.model

/**
 * Represents an open PDF tab with full state preservation.
 */
data class PdfTab(
    val id: String,
    val documentUri: String,
    val documentName: String,
    val currentPage: Int = 0,
    val zoomLevel: Float = 1.0f,
    val scrollPosition: Int = 0,
    val isPasswordProtected: Boolean = false,
    val viewMode: String = ViewMode.CONTINUOUS_VERTICAL.name,
    val viewerTheme: String = ViewerTheme.LIGHT.name
)
