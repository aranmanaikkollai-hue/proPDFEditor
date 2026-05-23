package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    indices = [Index(value = ["name"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val color: Int = 0xFF0061A4.toInt(),
    val icon: String = "folder",
    val documentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val isSystem: Boolean = false
)
