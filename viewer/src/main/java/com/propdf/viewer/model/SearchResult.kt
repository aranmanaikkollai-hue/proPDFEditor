package com.propdf.viewer.model

import android.graphics.RectF

/**
 * Represents a single search hit within a PDF document.
 */
data class SearchResult(
    val pageIndex: Int,
    val query: String = "",
    val matchCount: Int = 0,
    val textSnippet: String = "",
    val matchPositions: List<Int> = emptyList(),
    val boundingBoxes: List<RectF> = emptyList()
)
