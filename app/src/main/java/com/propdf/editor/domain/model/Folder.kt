package com.propdf.editor.domain.model

data class Folder(
    val id: Long = 0,
    val name: String,
    val color: Int = 0xFF0061A4.toInt(),
    val icon: String = "folder",
    val documentCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val isSystem: Boolean = false
)
