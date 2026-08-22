package com.propdf.core.domain.model

data class RecentFile(
    val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val thumbnailUri: String? = null,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val pageCount: Int = 0,
    val lastPageRead: Int = 0,
    val category: String = ""
)
