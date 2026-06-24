package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "collections",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["name"]),
        Index(value = ["sortOrder"])
    ]
)
data class CollectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String? = null,
    val parentId: String? = null,
    val color: Int? = null,
    val iconName: String? = null,
    val sortOrder: Int = 0,
    val isSmartFolder: Boolean = false,
    val smartRules: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
