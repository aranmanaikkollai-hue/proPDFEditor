package com.propdf.core.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for recently opened PDF documents.
 * Used by RecentFilesRepositoryImpl and provided via DatabaseModule.
 */
@Dao
interface RecentFilesDao {

    @Query("SELECT * FROM recent_files ORDER BY last_opened_at DESC")
    fun getAll(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE is_favourite = 1 ORDER BY last_opened_at DESC")
    fun getFavourites(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE category = :category ORDER BY last_opened_at DESC")
    fun getByCategory(category: String): Flow<List<RecentFileEntity>>

    @Query("SELECT DISTINCT category FROM recent_files WHERE category != '' ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    @Query(
        """
        SELECT * FROM recent_files
        WHERE display_name LIKE '%' || :query || '%'
        ORDER BY last_opened_at DESC
        """
    )
    fun search(query: String): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): RecentFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("UPDATE recent_files SET is_favourite = :isFavourite WHERE uri = :uri")
    suspend fun setFavourite(uri: String, isFavourite: Boolean)

    @Query("UPDATE recent_files SET category = :category WHERE uri = :uri")
    suspend fun setCategory(uri: String, category: String)

    @Query("UPDATE recent_files SET page_count = :count WHERE uri = :uri")
    suspend fun updatePageCount(uri: String, count: Int)

    /**
     * Deletes non-favourite entries, preserving files the user has starred.
     */
    @Query("DELETE FROM recent_files WHERE is_favourite = 0")
    suspend fun clearRecentOnly()

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()
}
