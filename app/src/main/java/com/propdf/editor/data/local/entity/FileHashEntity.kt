package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_hashes",
    indices = [
        Index(value = ["fastHash"]),
        Index(value = ["strongHash"]),
        Index(value = ["duplicateGroupId"])
    ]
)
data class FileHashEntity(
    @PrimaryKey
    val documentId: String,
    val fileSize: Long,
    val fastHash: String,
    val strongHash: String? = null,
    val pageCount: Int,
    val duplicateGroupId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long = System.currentTimeMillis()
)
