package com.propdf.viewer.model

import android.graphics.Bitmap

/**
 * Represents a thumbnail for a single PDF page.
 */
data class ThumbnailPage(
    val pageIndex: Int,
    val bitmap: Bitmap
)
