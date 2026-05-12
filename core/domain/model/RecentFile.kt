package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

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

@Parcelize
data class Bookmark(
    val documentUri: String,
    val pageIndex: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
