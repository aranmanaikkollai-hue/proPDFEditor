package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val documentUris: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val color: Int = 0
)
