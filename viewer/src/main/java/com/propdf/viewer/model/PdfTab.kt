package com.propdf.viewer.model

data class PdfTab(
    val id: String,
    val documentUri: String,
    val documentName: String,
    val currentPage: Int = 0,
    val zoomLevel: Float = 1.0f,
    val scrollPosition: Int = 0,
    val isPasswordProtected: Boolean = false,
    val viewMode: String = "CONTINUOUS_VERTICAL",
    val theme: String = "LIGHT"
)
