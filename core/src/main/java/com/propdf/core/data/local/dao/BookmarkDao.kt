package com.propdf.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.propdf.core.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT page_index FROM bookmarks WHERE uri_string = :uriString ORDER BY page_index ASC")
    fun observeBookmarkedPages(uriString: String): Flow<List<Int>>

    @Query("SELECT page_index FROM bookmarks WHERE uri_string = :uriString ORDER BY page_index ASC")
    suspend fun getBookmarkedPages(uriString: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookmark(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE uri_string = :uriString AND page_index = :pageIndex")
    suspend fun removeBookmark(uriString: String, pageIndex: Int)

    @Query("DELETE FROM bookmarks WHERE uri_string = :uriString")
    suspend fun clearBookmarksForDocument(uriString: String)
}
