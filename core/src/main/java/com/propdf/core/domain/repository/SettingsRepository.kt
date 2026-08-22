package com.propdf.core.domain.repository

interface SettingsRepository {
    suspend fun getStorageStats(): StorageStats
}

data class StorageStats(
    val used: Long,
    val total: Long,
    val recycleBinCount: Int
)
