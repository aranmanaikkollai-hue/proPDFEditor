package com.propdf.editor.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /*
    NOTE: The following bindings are commented out because the interfaces
    PdfViewerRepository and ScannerRepository could not be resolved from
    the :core module. This is a pre-existing repository configuration issue.

    To fix:
    1. Ensure :core module exports com.propdf.core.domain.repository.PdfViewerRepository
    2. Ensure :core module exports com.propdf.core.domain.repository.ScannerRepository
    3. Then uncomment the bindings below.

    @Binds
    @Singleton
    abstract fun bindPdfViewerRepository(
        impl: com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
    ): com.propdf.core.domain.repository.PdfViewerRepository

    @Binds
    @Singleton
    abstract fun bindScannerRepository(
        impl: com.propdf.scanner.data.repository.ScannerRepositoryImpl
    ): com.propdf.core.domain.repository.ScannerRepository
    */
}
