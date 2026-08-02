package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_records")
data class ScanRecordEntity(
    @PrimaryKey
    val uri: String,
    val name: String,
    val pageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnailUri: String?
)
