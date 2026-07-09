package com.propdf.scanner.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.propdf.scanner.model.ColorFilter
import com.propdf.scanner.model.ScanMode

@Entity(tableName = "scanned_documents")
data class ScannedDocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scanMode: ScanMode,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "scanned_pages",
    foreignKeys = [ForeignKey(
        entity = ScannedDocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["documentId"])]
)
data class ScannedPageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val originalImagePath: String,
    val processedImagePath: String?,
    val edgeTopLeftX: Float, val edgeTopLeftY: Float,
    val edgeTopRightX: Float, val edgeTopRightY: Float,
    val edgeBottomRightX: Float, val edgeBottomRightY: Float,
    val edgeBottomLeftX: Float, val edgeBottomLeftY: Float,
    val colorFilter: ColorFilter,
    val rotation: Int,
    val timestamp: Long,
    val pageNumber: Int
)
