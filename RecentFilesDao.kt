package com.propdf.editor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFilesDao {

    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC")
    fun getAll(): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()

    @Query("UPDATE recent_files SET isFavourite = :fav WHERE uri = :uri")
    suspend fun setFavourite(uri: String, fav: Boolean)

    @Query("UPDATE recent_files SET category = :cat WHERE uri = :uri")
    suspend fun setCategory(uri: String, cat: String)

    // Clears ONLY non-starred, non-categorized files.
    // Starred (isFavourite=true) and categorized files are preserved.
    @Query("DELETE FROM recent_files WHERE isFavourite = 0 AND (category IS NULL OR category = '')")
    suspend fun clearRecentOnly()
}
