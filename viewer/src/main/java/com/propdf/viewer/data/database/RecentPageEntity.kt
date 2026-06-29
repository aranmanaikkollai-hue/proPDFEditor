package com.propdf.viewer.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting recently viewed pages per document.
 */
@Entity(
    tableName = "recent_pages",
    indices = [Index(value = ["documentUri", "pageIndex"], unique = true)]
)
data class RecentPageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val pageIndex: Int,
    val viewCount: Int = 1,
    val lastViewed: Long = System.currentTimeMillis()
)
