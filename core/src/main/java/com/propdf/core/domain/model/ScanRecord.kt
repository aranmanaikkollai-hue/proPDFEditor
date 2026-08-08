package com.propdf.core.domain.model

data class ScanRecord(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val createdAt: Long,
    val thumbnailUri: String? = null
)
