package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey
    val documentUri: String,
    val currentPage: Int,
    val totalPages: Int,
    val lastReadAt: Long = System.currentTimeMillis()
)
