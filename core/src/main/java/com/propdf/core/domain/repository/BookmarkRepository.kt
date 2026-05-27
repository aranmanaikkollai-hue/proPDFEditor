package com.propdf.core.domain.repository

import com.propdf.core.domain.model.Bookmark
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeByDocument(documentUri: String): Flow<List<Bookmark>>
    suspend fun add(bookmark: Bookmark): AppResult<Unit>
    suspend fun remove(bookmarkId: Long): AppResult<Unit>
    suspend fun update(bookmark: Bookmark): AppResult<Unit>
}
