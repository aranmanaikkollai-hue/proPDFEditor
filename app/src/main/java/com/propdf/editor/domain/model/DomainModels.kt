package com.propdf.editor.domain.model

import android.net.Uri

data class PdfDocument(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val fileSize: Long,
    val pageCount: Int,
    val lastModified: Long,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val category: DocumentCategory = DocumentCategory.UNCATEGORIZED,
    val cloudProvider: String? = null
)

enum class DocumentCategory(val displayName: String) {
    WORK("Work"),
    PERSONAL("Personal"),
    EDUCATION("Education"),
    FINANCE("Finance"),
    HEALTH("Health"),
    LEGAL("Legal"),
    UNCATEGORIZED("Uncategorized")
}

enum class ViewMode {
    LIST, GRID, TILE
}

data class Folder(
    val id: String,
    val name: String,
    val color: Long,
    val documentCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

data class StorageStats(
    val totalDocuments: Int = 0,
    val totalSize: Long = 0,
    val favoriteCount: Int = 0,
    val deletedCount: Int = 0
)
