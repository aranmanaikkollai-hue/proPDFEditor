package com.propdf.core.domain.repository

import com.propdf.core.domain.model.Bookmark
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun observeByDocument(documentUri: String): Flow<List<Bookmark>>
    suspend fun add(bookmark: Bookmark): AppResult<Unit>
    suspend fun remove(documentUri: String, pageIndex: Int): AppResult<Unit>
    suspend fun updateLabel(documentUri: String, pageIndex: Int, newLabel: String): AppResult<Unit>
    suspend fun clearByDocument(documentUri: String): AppResult<Unit>
}
