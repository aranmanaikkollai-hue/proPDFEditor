package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recycle_bin")
data class RecycleBinEntity(
    @PrimaryKey
    val originalUri: String,
    val name: String,
    val size: Long,
    val deletedAt: Long = System.currentTimeMillis(),
    val originalPath: String,
    val willBePermanentlyDeletedAt: Long // 30 days from deletion
)
