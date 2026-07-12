package com.propdf.editor.domain.model

import android.net.Uri

data class PdfDocument(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val fileSize: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val category: DocumentCategory = DocumentCategory.UNCATEGORIZED,
    val cloudProvider: String? = null,
    val pageCount: Int = 0
)

enum class DocumentCategory(val displayName: String) {
    UNCATEGORIZED("Uncategorized"),
    WORK("Work"),
    PERSONAL("Personal"),
    FINANCE("Finance"),
    EDUCATION("Education"),
    HEALTH("Health"),
    LEGAL("Legal"),
    OTHER("Other")
}

data class StorageStats(
    val totalDocuments: Int = 0,
    val totalSize: Long = 0,
    val favoriteCount: Int = 0,
    val deletedCount: Int = 0
)

data class Folder(
    val id: String,
    val name: String,
    val color: Long,
    val documentCount: Int = 0
)
