package com.propdf.editor.domain.model

data class StorageStats(
    val totalDocuments: Int = 0,
    val totalSize: Long = 0,
    val favoriteCount: Int = 0,
    val deletedCount: Int = 0,
    val cloudDocuments: Int = 0,
    val categoryBreakdown: Map<String, Int> = emptyMap()
)
