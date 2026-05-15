package com.propdf.viewer.presentation

import android.graphics.Bitmap

/**
 * Data class for thumbnail items in the sidebar.
 */
data class ThumbnailItem(
    val pageIndex: Int,
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false
)
