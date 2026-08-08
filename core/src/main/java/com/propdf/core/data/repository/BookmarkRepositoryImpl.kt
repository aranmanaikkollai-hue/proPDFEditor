package com.propdf.core.data.repository

import com.propdf.core.data.database.dao.BookmarkDao
import com.propdf.core.data.database.entity.BookmarkEntity
import com.propdf.core.domain.repository.Bookmark
import com.propdf.core.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao
) : BookmarkRepository {

    override suspend fun addBookmark(uri: String, page: Int, title: String) {
        bookmarkDao.insert(
            BookmarkEntity(
                documentUri = uri,
                page = page,
                title = title
            )
        )
    }

    override suspend fun removeBookmark(uri: String, page: Int) {
        bookmarkDao.delete(uri, page)
    }

    override suspend fun getBookmarks(uri: String): List<Bookmark> {
        return bookmarkDao.getByDocument(uri).map {
            Bookmark(
                uri = it.documentUri,
                page = it.page,
                title = it.title,
                createdAt = it.createdAt
            )
        }
    }
}
