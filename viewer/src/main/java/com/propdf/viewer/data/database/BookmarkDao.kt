package com.propdf.viewer.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE documentUri = :documentUri ORDER BY pageIndex ASC")
    fun getBookmarksForDocument(documentUri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE documentUri = :documentUri ORDER BY pageIndex ASC")
    suspend fun getBookmarksForDocumentSync(documentUri: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteBookmarkForPage(documentUri: String, pageIndex: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE documentUri = :documentUri AND pageIndex = :pageIndex)")
    suspend fun isPageBookmarked(documentUri: String, pageIndex: Int): Boolean
}
