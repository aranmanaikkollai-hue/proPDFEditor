package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_files",
    indices = [Index(value = ["uri"], unique = true)]
)
data class RecentFileEntity(
    @PrimaryKey
    val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val thumbnailUri: String?,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val pageCount: Int = 0,
    val lastPageRead: Int = 0
)
