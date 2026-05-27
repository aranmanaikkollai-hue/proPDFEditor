package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Domain model representing a recently opened PDF document.
 */
@Parcelize
data class RecentFile(
    val uri: String,
    val displayName: String,
    val fileSizeBytes: Long = 0,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val isFavourite: Boolean = false,
    val category: String = ""
) : Parcelable

/**
 * Domain model representing a bookmark within a PDF document.
 */
@Parcelize
data class Bookmark(
    val documentUri: String,
    val pageIndex: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
