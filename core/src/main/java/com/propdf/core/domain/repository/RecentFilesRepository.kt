package com.propdf.core.domain.repository

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing recently opened PDF files.
 * All operations return [AppResult] for consistent error handling.
 */
interface RecentFilesRepository {
    /** Observe all recent files, ordered by last opened time (descending). */
    fun observeAll(): Flow<List<RecentFile>>

    /** Observe only favourite files. */
    fun observeFavourites(): Flow<List<RecentFile>>

    /** Observe files filtered by category. */
    fun observeByCategory(category: String): Flow<List<RecentFile>>

    /** Observe all distinct categories. */
    fun observeCategories(): Flow<List<String>>

    /** Search files by query string (matches display name). */
    fun search(query: String): Flow<List<RecentFile>>

    /** Add or update a recent file entry. */
    suspend fun add(file: RecentFile): AppResult<Unit>

    /** Remove a file from recent files (soft delete). */
    suspend fun remove(uri: String): AppResult<Unit>

    /** Toggle favourite status for a file. */
    suspend fun setFavourite(uri: String, isFavourite: Boolean): AppResult<Unit>

    /** Set category for a file. */
    suspend fun setCategory(uri: String, category: String): AppResult<Unit>

    /** Update page count for a file. */
    suspend fun updatePageCount(uri: String, count: Int): AppResult<Unit>

    /** Clear only recent files (keep favourites). */
    suspend fun clearRecentOnly(): AppResult<Unit>

    /** Clear all recent files including favourites. */
    suspend fun clearAll(): AppResult<Unit>

    /** Get a single file by URI. */
    suspend fun getByUri(uri: String): AppResult<RecentFile>
}
