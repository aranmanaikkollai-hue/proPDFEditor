package com.propdf.annotations.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * Room entity for annotation persistence.
 * Stores annotation data as JSON for flexibility.
 */
@Entity(tableName = "annotations")
@TypeConverters(AnnotationConverters::class)
data class AnnotationEntity(
    @PrimaryKey
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val type: String,
    val jsonData: String, // Serialized annotation data
    val zIndex: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val isVisible: Boolean = true
)
