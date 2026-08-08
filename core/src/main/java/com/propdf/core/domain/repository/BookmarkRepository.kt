package com.propdf.core.domain.repository

interface BookmarkRepository {
    suspend fun addBookmark(uri: String, page: Int, title: String)
    suspend fun removeBookmark(uri: String, page: Int)
    suspend fun getBookmarks(uri: String): List<Bookmark>
}

data class Bookmark(
    val uri: String,
    val page: Int,
    val title: String,
    val createdAt: Long
)
