package com.propdf.editor.di

import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreRepositoryBindingsModule {

    @Binds
    @Singleton
    abstract fun bindRecentFilesRepository(
        impl: StubRecentFilesRepository
    ): RecentFilesRepository

    @Binds
    @Singleton
    abstract fun bindPdfViewerRepository(
        impl: StubPdfViewerRepository
    ): PdfViewerRepository
}
