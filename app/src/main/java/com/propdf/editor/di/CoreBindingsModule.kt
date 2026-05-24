package com.propdf.editor.di

import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides stub bindings for :core module repository interfaces
 * that are injected by existing ViewModels but have no implementations.
 *
 * These stubs satisfy Dagger/Hilt compilation. Replace with real
 * implementations when the :core module is fully wired.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreBindingsModule {

    @Provides
    @Singleton
    fun provideRecentFilesRepository(): RecentFilesRepository {
        return object : RecentFilesRepository {
            // Stub implementation - replace with real logic
        }
    }

    @Provides
    @Singleton
    fun providePdfViewerRepository(): PdfViewerRepository {
        return object : PdfViewerRepository {
            // Stub implementation - replace with real logic
        }
    }
}
