package com.propdf.core.data.repository

import com.propdf.core.data.database.dao.RecentFileDao
import com.propdf.core.data.database.entity.RecentFileEntity
import com.propdf.core.domain.model.ReadingProgress
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentFileRepositoryImpl @Inject constructor(
    private val recentFileDao: RecentFileDao
) : RecentFileRepository {

    override fun observeRecentFiles(limit: Int): Flow<List<RecentFile>> {
        return recentFileDao.observeRecent(limit).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getRecentFiles(limit: Int): List<RecentFile> {
        return recentFileDao.getRecent(limit).map { it.toDomain() }
    }

    override suspend fun getPinnedFiles(): List<RecentFile> {
        return recentFileDao.getPinned().map { it.toDomain() }
    }

    override suspend fun getFavoriteFiles(): List<RecentFile> {
        return recentFileDao.getFavorites().map { it.toDomain() }
    }

    override suspend fun getContinueReading(): ReadingProgress? {
        // Get most recent file with progress
        val recent = recentFileDao.getRecent(1).firstOrNull() ?: return null
        return ReadingProgress(
            file = recent.toDomain(),
            currentPage = recent.lastPageRead,
            totalPages = recent.pageCount,
            percentage = if (recent.pageCount > 0) recent.lastPageRead.toFloat() / recent.pageCount else 0f
        )
    }

    override suspend fun addToRecent(file: RecentFile) {
        recentFileDao.insert(file.toEntity())
    }

    override suspend fun pinFile(uri: String, pin: Boolean) {
        recentFileDao.setPinned(uri, pin)
    }

    override suspend fun favoriteFile(uri: String, favorite: Boolean) {
        recentFileDao.setFavorite(uri, favorite)
    }

    override suspend fun deleteFile(uri: String) {
        recentFileDao.delete(uri)
    }

    private fun RecentFileEntity.toDomain() = RecentFile(
        uri = uri,
        name = name,
        size = size,
        lastOpened = lastOpened,
        thumbnailUri = thumbnailUri,
        isPinned = isPinned,
        isFavorite = isFavorite,
        pageCount = pageCount,
        lastPageRead = lastPageRead
    )

    private fun RecentFile.toEntity() = RecentFileEntity(
        uri = uri,
        name = name,
        size = size,
        lastOpened = lastOpened,
        thumbnailUri = thumbnailUri,
        isPinned = isPinned,
        isFavorite = isFavorite,
        pageCount = pageCount,
        lastPageRead = lastPageRead
    )
}
