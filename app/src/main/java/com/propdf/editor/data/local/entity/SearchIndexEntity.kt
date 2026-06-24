package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_index",
    foreignKeys = [
        ForeignKey(
            entity = PdfDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"], unique = true),
        Index(value = ["ocrText"])
    ]
)
data class SearchIndexEntity(
    @PrimaryKey
    val documentId: String,
    val fileName: String,
    val contentText: String?,
    val ocrText: String? = null,
    val pageCount: Int = 0,
    val indexedAt: Long = System.currentTimeMillis(),
    val indexingStatus: IndexingStatus = IndexingStatus.PENDING,
    val lastAccessed: Long = System.currentTimeMillis()
)

enum class IndexingStatus {
    PENDING, PROCESSING, COMPLETED, FAILED, PARTIAL
}
