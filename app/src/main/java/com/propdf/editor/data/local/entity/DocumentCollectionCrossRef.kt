package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_collection_cross_ref",
    primaryKeys = ["documentId", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = PdfDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["collectionId"]),
        Index(value = ["documentId", "collectionId"], unique = true)
    ]
)
data class DocumentCollectionCrossRef(
    val documentId: String,
    val collectionId: String,
    val addedAt: Long = System.currentTimeMillis()
)
