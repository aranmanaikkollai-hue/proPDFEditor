package com.propdf.viewer.model

/**
 * Represents an entry in the page navigation history.
 */
data class PageHistoryEntry(
    val pageIndex: Int,
    val timestamp: Long
)
