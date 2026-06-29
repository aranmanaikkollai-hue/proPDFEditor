package com.propdf.viewer.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting PDF bookmarks.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["documentUri", "pageIndex"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val label: String,
    val timestamp: Long = System.currentTimeMillis()
)
