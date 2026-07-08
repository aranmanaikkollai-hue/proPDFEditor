package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// NOTE: No @ForeignKey on documentId -> PdfDocumentEntity.id (Long vs String type
// mismatch - see the same note in DocumentCollectionCrossRef.kt). The unique index
// on documentId is kept for lookup performance and to prevent duplicate rows.
@Entity(
    tableName = "search_index",
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
