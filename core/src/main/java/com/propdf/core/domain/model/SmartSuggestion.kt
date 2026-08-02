package com.propdf.core.domain.model

data class SmartSuggestion(
    val id: String,
    val title: String,
    val subtitle: String,
    val action: String, // "open", "scan", "compress"
    val target: String,
    val priority: Int
)
