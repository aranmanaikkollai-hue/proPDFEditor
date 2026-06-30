package com.propdf.editor.di

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

    // PdfViewerRepository is bound in com.propdf.viewer.di.ViewerBindsModule
    // (to PdfViewerRepositoryImpl). A duplicate @Binds for it used to live
    // here pointing at StubPdfViewerRepository, which caused a
    // [Dagger/DuplicateBindings] error once the real :viewer implementation
    // was wired up.
}
