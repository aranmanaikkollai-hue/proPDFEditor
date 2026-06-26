package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pdf_documents",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["fileName"]),
        Index(value = ["lastModified"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isScanned"]),
        Index(value = ["category"]),
        Index(value = ["folderId"])
    ]
)
data class PdfDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val fileName: String,
    val displayName: String = "",
    val fileSize: Long,
    val pageCount: Int = 0,
    val thumbnailUri: String? = null,
    val lastModified: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpened: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isScanned: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val category: String = "UNCATEGORIZED",
    val folderId: Long? = null,
    val searchText: String = "",
    val cloudProvider: String? = null,
    val cloudId: String? = null,
    val syncStatus: String = "SYNCED",

    // Phase 6 fields
    val documentType: String? = null,
    val autoRenamed: Boolean = false,
    val originalFileName: String? = null,
    val lastIndexedAt: Long? = null,
    val ocrConfidence: Float? = null
)
