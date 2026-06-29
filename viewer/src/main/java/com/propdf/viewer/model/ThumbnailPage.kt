package com.propdf.viewer.model

import android.graphics.Bitmap

/**
 * Represents a rendered thumbnail page for the sidebar.
 */
data class ThumbnailPage(
    val pageIndex: Int,
    val bitmap: Bitmap
)
