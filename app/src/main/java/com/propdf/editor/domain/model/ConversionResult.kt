package com.propdf.editor.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ConversionResult(
    val success: Boolean,
    val outputUri: Uri?,
    val outputFileName: String,
    val message: String,
    val fileCount: Int = 1,
    val totalBytes: Long = 0L
) : Parcelable
