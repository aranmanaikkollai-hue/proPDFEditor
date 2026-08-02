package com.propdf.core.domain.repository

import com.propdf.core.domain.model.ReadingProgress
import com.propdf.core.domain.model.RecentFile
import kotlinx.coroutines.flow.Flow

interface RecentFileRepository {
    fun observeRecentFiles(limit: Int): Flow<List<RecentFile>>
    suspend fun getRecentFiles(limit: Int): List<RecentFile>
    suspend fun getPinnedFiles(): List<RecentFile>
    suspend fun getFavoriteFiles(): List<RecentFile>
    suspend fun getContinueReading(): ReadingProgress?
    suspend fun addToRecent(file: RecentFile)
    suspend fun pinFile(uri: String, pin: Boolean)
    suspend fun favoriteFile(uri: String, favorite: Boolean)
    suspend fun deleteFile(uri: String)
}
