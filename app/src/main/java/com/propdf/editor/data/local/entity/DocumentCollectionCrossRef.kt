package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// NOTE: No @ForeignKey on documentId -> PdfDocumentEntity.id. PdfDocumentEntity.id
// is a Long (autoGenerate), but documentId here is a String (callers always pass
// doc.id.toString(), see DocumentIndexWorker/SmartFolderRefreshWorker/DuplicateScanWorker).
// Room's annotation processor requires parent/child FK column types to match exactly,
// and a Long<->String mismatch fails Room schema validation during :app's kapt stub
// generation (surfaces as the generic "Could not load module <Error module>" kapt
// error rather than a clear Room diagnostic). The collectionId -> CollectionEntity.id
// FK is kept since both sides are String and match.
@Entity(
    tableName = "document_collection_cross_ref",
    primaryKeys = ["documentId", "collectionId"],
    foreignKeys = [
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
