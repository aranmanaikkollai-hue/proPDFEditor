package com.propdf.core.domain.model

data class ReadingProgress(
    val file: RecentFile,
    val currentPage: Int,
    val totalPages: Int,
    val percentage: Float
)
