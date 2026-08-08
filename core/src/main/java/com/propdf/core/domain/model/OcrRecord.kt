package com.propdf.core.domain.model

data class OcrRecord(
    val id: String,
    val sourceUri: String,
    val extractedText: String,
    val language: String,
    val createdAt: Long
)
