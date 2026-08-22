package com.propdf.core.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signatures")
data class SignatureEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val filePath: String,
    val type: String, // "drawn", "typed", "image"
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
