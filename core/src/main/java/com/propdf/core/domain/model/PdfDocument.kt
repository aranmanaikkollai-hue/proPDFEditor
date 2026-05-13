package com.propdf.core.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Immutable domain model representing a PDF document.
 * Used across all modules — never expose framework types in domain.
 */
@Parcelize
data class PdfDocument(
    val uri: String,
    val displayName: String,
    val pageCount: Int = 0,
    val fileSizeBytes: Long = 0,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val isPasswordProtected: Boolean = false
) : Parcelable {
    fun toUri(): Uri = Uri.parse(uri)
}
