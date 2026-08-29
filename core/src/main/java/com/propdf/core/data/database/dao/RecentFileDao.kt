package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.RecentFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpened DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files ORDER BY lastOpened DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<RecentFileEntity>

    @Query("SELECT * FROM recent_files WHERE isPinned = 1 ORDER BY lastOpened DESC")
    suspend fun getPinned(): List<RecentFileEntity>

    @Query("SELECT * FROM recent_files WHERE isFavorite = 1 ORDER BY lastOpened DESC")
    suspend fun getFavorites(): List<RecentFileEntity>

    @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): RecentFileEntity?

    @Query("SELECT * FROM recent_files WHERE name LIKE '%' || :query || '%' ORDER BY lastOpened DESC")
    fun search(query: String): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: RecentFileEntity)

    @Query("UPDATE recent_files SET isPinned = :pinned WHERE uri = :uri")
    suspend fun setPinned(uri: String, pinned: Boolean)

    @Query("UPDATE recent_files SET isFavorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_files WHERE lastOpened < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    /**
     * Clears recent-history entries only. Preserves pinned and favorited
     * files -- this table only ever stores metadata (uri, name, timestamps,
     * flags) about files the user opened elsewhere, so this query, and
     * "Clear Recent Files" built on it, can never touch the underlying PDF,
     * a folder, or the separate recycle-bin feature; it only forgets that a
     * given file was recently opened.
     */
    @Query("DELETE FROM recent_files WHERE isFavorite = 0 AND isPinned = 0")
    suspend fun clearRecentOnly()

    @Query("SELECT COUNT(*) FROM recent_files")
    suspend fun count(): Int
}
