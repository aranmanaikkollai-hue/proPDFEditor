package com.propdf.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.core.domain.model.OcrJobStatus

@Entity(tableName = "ocr_jobs")
@TypeConverters(OcrConverters::class)
data class OcrJobEntity(
    @PrimaryKey
    val id: String,
    val imageUrisJson: String,
    val languageCodesJson: String,
    val outputFormat: String,
    val status: OcrJobStatus,
    val progress: Int,
    val totalPages: Int,
    val completedPages: Int,
    val resultUri: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val completedAt: Long?
)
