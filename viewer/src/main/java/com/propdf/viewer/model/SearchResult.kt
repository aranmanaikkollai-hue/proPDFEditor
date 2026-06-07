package com.propdf.viewer.model

import android.graphics.RectF

/**
 * Represents a single search result within a PDF page.
 */
data class SearchResult(
    val pageIndex: Int,
    val textSnippet: String,
    val matchCount: Int,
    val boundingBoxes: List<RectF>
)
