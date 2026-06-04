package com.propdf.viewer.model

/**
 * Represents a single open PDF document tab in the viewer.
 */
data class PdfTab(
    val id: String,
    val documentUri: String,
    val documentName: String,
    val currentPage: Int = 0,
    val zoomLevel: Float = 1.0f,
    val viewMode: String = ViewMode.CONTINUOUS_VERTICAL.name,
    val theme: String = ViewerTheme.LIGHT.name,
    val scrollPosition: Int = 0
)
