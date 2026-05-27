package com.propdf.core.domain.model

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ScannedPage(
    val id: Long,
    val bitmap: Bitmap,
    val originalBitmap: Bitmap,
    val corners: List<PointF>?,
    val filter: String = "AUTO"
) : Parcelable
