package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_index",
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["word"])
    ]
)
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: Long,
    val pageNumber: Int,
    val word: String,
    val context: String,
    val positionX: Float = 0f,
    val positionY: Float = 0f
)
