package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE documentUri = :documentUri ORDER BY page ASC")
    fun observeByDocument(documentUri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE documentUri = :documentUri ORDER BY page ASC")
    suspend fun getByDocument(documentUri: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE documentUri = :documentUri AND page = :page")
    suspend fun delete(documentUri: String, page: Int)

    @Query("DELETE FROM bookmarks WHERE documentUri = :documentUri")
    suspend fun deleteAllForDocument(documentUri: String)
}
