package com.propdf.core.data.local

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentFilesRepositoryImpl @Inject constructor(
    private val dao: RecentFilesDao
) : RecentFilesRepository {

    override fun observeAll(): Flow<List<RecentFile>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeFavourites(): Flow<List<RecentFile>> =
        dao.getFavourites().map { list -> list.map { it.toDomain() } }

    override fun observeByCategory(category: String): Flow<List<RecentFile>> =
        dao.getByCategory(category).map { list -> list.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<String>> = dao.getCategories()

    override fun search(query: String): Flow<List<RecentFile>> =
        dao.search(query).map { list -> list.map { it.toDomain() } }

    override suspend fun add(file: RecentFile): AppResult<Unit> = try {
        dao.insert(file.toEntity())
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun remove(uri: String): AppResult<Unit> = try {
        dao.delete(uri)
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun setFavourite(uri: String, isFavourite: Boolean): AppResult<Unit> = try {
        dao.setFavourite(uri, isFavourite)
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun setCategory(uri: String, category: String): AppResult<Unit> = try {
        dao.setCategory(uri, category)
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun updatePageCount(uri: String, count: Int): AppResult<Unit> = try {
        dao.updatePageCount(uri, count)
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun clearRecentOnly(): AppResult<Unit> = try {
        dao.clearRecentOnly()
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    override suspend fun clearAll(): AppResult<Unit> = try {
        dao.clearAll()
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    // FIX: interface declares AppResult<RecentFile> (non-nullable), not AppResult<RecentFile?>
    // Return FileNotFound error when dao returns null instead of wrapping null in Success
    override suspend fun getByUri(uri: String): AppResult<RecentFile> = try {
        val entity = dao.getByUri(uri)
        if (entity != null) {
            AppResult.Success(entity.toDomain())
        } else {
            AppResult.Error(AppException.FileNotFound("No file found for URI: $uri"))
        }
    } catch (e: Exception) {
        AppResult.Error(e.toAppException())
    }

    private fun RecentFileEntity.toDomain() = RecentFile(
        uri = uri,
        displayName = displayName,
        fileSizeBytes = fileSizeBytes,
        lastOpenedAt = lastOpenedAt,
        pageCount = pageCount,
        isFavourite = isFavourite,
        category = category
    )

    private fun RecentFile.toEntity() = RecentFileEntity(
        uri = uri,
        displayName = displayName,
        fileSizeBytes = fileSizeBytes,
        lastOpenedAt = lastOpenedAt,
        pageCount = pageCount,
        isFavourite = isFavourite,
        category = category
    )
}
