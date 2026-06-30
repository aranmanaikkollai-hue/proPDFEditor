package com.propdf.editor.di

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides stub bindings for :core module repository interfaces
 * that are injected by existing ViewModels but have no implementations.
 *
 * These stubs satisfy Dagger/Hilt compilation. Replace with real
 * implementations when the :core module is fully wired.
 *
 * Note: PdfViewerRepository has a real implementation
 * (com.propdf.viewer.data.repository.PdfViewerRepositoryImpl, bound via
 * com.propdf.viewer.di.ViewerBindsModule), so no stub is needed for it here.
 */
@Singleton
class StubRecentFilesRepository @Inject constructor() : RecentFilesRepository {
    override fun observeAll(): Flow<List<RecentFile>> = flowOf(emptyList())
    override fun observeFavourites(): Flow<List<RecentFile>> = flowOf(emptyList())
    override fun observeByCategory(category: String): Flow<List<RecentFile>> = flowOf(emptyList())
    override fun observeCategories(): Flow<List<String>> = flowOf(emptyList())
    override fun search(query: String): Flow<List<RecentFile>> = flowOf(emptyList())
    override suspend fun add(file: RecentFile): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun remove(uri: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun setFavourite(uri: String, isFavourite: Boolean): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun setCategory(uri: String, category: String): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun updatePageCount(uri: String, count: Int): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearRecentOnly(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun clearAll(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun getByUri(uri: String): AppResult<RecentFile> = AppResult.Error("Not found")
}
