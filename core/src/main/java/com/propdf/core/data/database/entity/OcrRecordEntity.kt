package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ocr_records")
data class OcrRecordEntity(
    @PrimaryKey
    val id: String,
    val sourceUri: String,
    val extractedText: String,
    val language: String,
    val createdAt: Long = System.currentTimeMillis()
)
