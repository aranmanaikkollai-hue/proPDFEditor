package com.propdf.core.domain.repository

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

interface RecentFilesRepository {
    fun observeAll(): Flow<List<RecentFile>>
    fun observeFavourites(): Flow<List<RecentFile>>
    fun observeByCategory(category: String): Flow<List<RecentFile>>
    fun observeCategories(): Flow<List<String>>
    fun search(query: String): Flow<List<RecentFile>>
    suspend fun add(file: RecentFile): AppResult<Unit>
    suspend fun remove(uri: String): AppResult<Unit>
    suspend fun setFavourite(uri: String, isFavourite: Boolean): AppResult<Unit>
    suspend fun setCategory(uri: String, category: String): AppResult<Unit>
    suspend fun updatePageCount(uri: String, count: Int): AppResult<Unit>
    suspend fun clearRecentOnly(): AppResult<Unit>
    suspend fun clearAll(): AppResult<Unit>
    suspend fun getByUri(uri: String): AppResult<RecentFile?>
}
