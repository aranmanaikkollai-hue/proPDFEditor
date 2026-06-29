package com.propdf.viewer.model

/**
 * Represents a bookmarked page in a PDF document.
 */
data class Bookmark(
    val pageIndex: Int,
    val timestamp: Long,
    val label: String
)
