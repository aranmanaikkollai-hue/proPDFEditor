package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// NOTE: No @ForeignKey on documentId -> PdfDocumentEntity.id (Long vs String type
// mismatch - see the same note in DocumentCollectionCrossRef.kt). The tagId ->
// TagEntity.id FK is kept since both sides are String and match.
@Entity(
    tableName = "document_tag_cross_ref",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["tagId"]),
        Index(value = ["documentId", "tagId"], unique = true)
    ]
)
data class DocumentTagCrossRef(
    val documentId: String,
    val tagId: String,
    val addedAt: Long = System.currentTimeMillis()
)
