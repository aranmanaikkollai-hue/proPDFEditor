package com.propdf.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a recently opened PDF document.
 * Maps 1:1 with the RecentFile domain model.
 */
@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0,

    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "page_count")
    val pageCount: Int = 0,

    @ColumnInfo(name = "is_favourite")
    val isFavourite: Boolean = false,

    @ColumnInfo(name = "category")
    val category: String = ""
)
