package com.propdf.editor.di

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides stub bindings for :core module repository interfaces
 * that are injected by existing ViewModels but have no implementations.
 *
 * These stubs satisfy Dagger/Hilt compilation. Replace with real
 * implementations when the :core module is fully wired.
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

@Singleton
class StubPdfViewerRepository @Inject constructor() : PdfViewerRepository {
    override suspend fun copyUriToCache(uri: Uri): AppResult<File> = AppResult.Error("Not implemented")
    override suspend fun getPageCount(file: File): AppResult<Int> = AppResult.Success(0)
    override suspend fun renderPage(file: File, pageIndex: Int, screenWidth: Int): AppResult<Bitmap> = AppResult.Error("Not implemented")
    override suspend fun getPageText(file: File, pageIndex: Int): AppResult<String> = AppResult.Success("")
    override suspend fun preloadPages(file: File, anchorPage: Int, screenWidth: Int): AppResult<Unit> = AppResult.Success(Unit)
    override fun clearCache() {}
}
