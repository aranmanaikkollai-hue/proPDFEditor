package com.propdf.sync.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watched_folders")
data class WatchedFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "tree_uri_string")
    val treeUriString: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "auto_import")
    val autoImport: Boolean = true,

    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long = 0,

    @ColumnInfo(name = "imported_count")
    val importedCount: Int = 0
)
