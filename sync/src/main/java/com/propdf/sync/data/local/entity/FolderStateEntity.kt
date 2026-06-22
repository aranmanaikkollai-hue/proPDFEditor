package com.propdf.sync.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folder_states",
    foreignKeys = [
        ForeignKey(
            entity = WatchedFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folder_id", "document_uri"], unique = true)]
)
data class FolderStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "folder_id")
    val folderId: Long,

    @ColumnInfo(name = "document_uri")
    val documentUri: String,

    @ColumnInfo(name = "document_name")
    val documentName: String,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    @ColumnInfo(name = "checked_at")
    val checkedAt: Long = System.currentTimeMillis()
)
