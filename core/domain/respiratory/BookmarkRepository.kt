package com.propdf.core.domain.repository

import com.propdf.core.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeForDocument(documentUri: String): Flow<List<Bookmark>>
    suspend fun add(bookmark: Bookmark)
    suspend fun remove(documentUri: String, pageIndex: Int)
    suspend fun updateLabel(documentUri: String, pageIndex: Int, label: String)
    suspend fun isBookmarked(documentUri: String, pageIndex: Int): Boolean
}
