package com.propdf.viewer.annotation.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "annotations",
    indices = [Index(value = ["pdfUri", "pageIndex"])]
)
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val pdfUri: String,
    val pageIndex: Int,
    val payloadJson: String,
    val updatedAt: Long
)
