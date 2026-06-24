package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val color: Int? = null,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
