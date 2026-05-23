package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "pdf_documents",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["fileName"]),
        Index(value = ["category"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isDeleted"]),
        Index(value = ["lastOpened"]),
        Index(value = ["searchText"])
    ]
)
data class PdfDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val fileName: String,
    val displayName: String,
    val fileSize: Long,
    val pageCount: Int = 0,
    val thumbnailUri: String? = null,
    val category: String = "Uncategorized",
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpened: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val searchText: String = "",
    val cloudProvider: String? = null,
    val cloudId: String? = null,
    val syncStatus: String = SyncStatus.SYNCED.name
)

enum class SyncStatus {
    SYNCED, PENDING_UPLOAD, PENDING_DOWNLOAD, CONFLICT, ERROR
}
