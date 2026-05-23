package com.propdf.editor.di

import com.propdf.core.data.local.RecentFilesRepositoryImpl
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRecentFilesRepository(
        impl: RecentFilesRepositoryImpl
    ): RecentFilesRepository

    @Binds
    @Singleton
    abstract fun bindPdfViewerRepository(
        impl: PdfViewerRepositoryImpl
    ): PdfViewerRepository
}
