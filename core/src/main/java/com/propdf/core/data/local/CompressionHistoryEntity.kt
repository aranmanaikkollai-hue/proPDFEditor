package com.propdf.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.core.domain.model.CompressionConfig
import java.util.Date

@Entity(tableName = "compression_history")
@TypeConverters(CompressionConverters::class)
data class CompressionHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceUri: String,
    val outputUri: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val compressionRatio: Float,
    val config: CompressionConfig,
    val timestamp: Date = Date(),
    val fileName: String
)
