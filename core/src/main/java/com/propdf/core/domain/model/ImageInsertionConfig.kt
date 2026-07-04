package com.propdf.core.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImageInsertionConfig(
    val imageUri: Uri,
    val pageNum: Int,
    val x: Float = -1f,
    val y: Float = -1f
) : Parcelable
