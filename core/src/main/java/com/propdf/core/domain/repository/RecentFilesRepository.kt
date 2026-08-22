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

    /** Rename a file's display name (label only — does not rename the underlying physical file/URI). */
    suspend fun rename(uri: String, newDisplayName: String): AppResult<Unit>

    /** Update page count for a file. */
    suspend fun updatePageCount(uri: String, count: Int): AppResult<Unit>

    /** Clear only recent files (keep favourites). */
    suspend fun clearRecentOnly(): AppResult<Unit>

    /** Clear all recent files including favourites. */
    suspend fun clearAll(): AppResult<Unit>

    /** Get a single file by URI. */
    suspend fun getByUri(uri: String): AppResult<RecentFile>

    /**
     * One-time migration: mirrors every existing recent-file row into
     * core.ProPDFDatabase's pdf_documents table. Needed because pdf_documents
     * had no writer before the add()/setFavourite() dual-write was added, so
     * users with existing history would otherwise never get backfilled.
     * Safe to call more than once — uses the same find-by-uri-then-update
     * logic as add(), so it won't duplicate or overwrite existing rows'
     * favorite/tag/collection state beyond what's tracked here.
     * Returns the number of rows backfilled.
     */
    suspend fun backfillDocumentTable(): Int
}
