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
        Index(value = ["documentType"])
    ]
)
data class PdfDocumentEntity(
    @PrimaryKey
    val id: String,
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val pageCount: Int,
    val thumbnailUri: String? = null,
    val lastModified: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val isScanned: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    // Phase 6 fields
    val documentType: String? = null,
    val autoRenamed: Boolean = false,
    val originalFileName: String? = null,
    val lastIndexedAt: Long? = null,
    val ocrConfidence: Float? = null
)
