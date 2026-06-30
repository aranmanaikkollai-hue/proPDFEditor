package com.propdf.annotations.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * Room entity for annotation persistence.
 * Stores annotation data as JSON for flexibility across all annotation types.
 *
 * Version 2: Added documentPath and isFlattened for cross-device sync and export tracking.
 */
@Entity(tableName = "annotations")
@TypeConverters(AnnotationConverters::class)
data class AnnotationEntity(
    @PrimaryKey
    val id: String,
    val documentId: String,
    val documentPath: String = "",
    val pageIndex: Int,
    val type: String,
    val jsonData: String,
    val zIndex: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val isVisible: Boolean = true,
    val isFlattened: Boolean = false
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}
