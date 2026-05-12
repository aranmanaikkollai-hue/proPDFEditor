package com.propdf.editor.di

import com.propdf.annotations.domain.usecase.ExportAnnotationsUseCase
import com.propdf.core.data.local.RecentFilesRepositoryImpl
import com.propdf.core.domain.repository.*
import com.propdf.editor.data.repository.PdfOperationsRepositoryImpl
import com.propdf.ocr.data.repository.OcrRepositoryImpl
import com.propdf.scanner.data.repository.ScannerRepositoryImpl
import com.propdf.security.domain.usecase.DecryptPdfUseCase
import com.propdf.security.domain.usecase.EncryptPdfUseCase
import com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires all repository implementations to their interfaces.
 * This is the ONLY place where concrete implementations are bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPdfViewerRepository(impl: PdfViewerRepositoryImpl): PdfViewerRepository

    @Binds
    @Singleton
    abstract fun bindPdfOperationsRepository(impl: PdfOperationsRepositoryImpl): PdfOperationsRepository

    @Binds
    @Singleton
    abstract fun bindOcrRepository(impl: OcrRepositoryImpl): OcrRepository

    @Binds
    @Singleton
    abstract fun bindScannerRepository(impl: ScannerRepositoryImpl): ScannerRepository

    @Binds
    @Singleton
    abstract fun bindRecentFilesRepository(impl: RecentFilesRepositoryImpl): RecentFilesRepository
}

@Module
@InstallIn(ViewModelComponent::class)
object UseCaseModule {

    @Provides
    fun provideEncryptPdfUseCase(
        dispatchers: com.propdf.core.domain.dispatcher.DispatcherProvider,
        pdfOps: PdfOperationsRepository
    ): EncryptPdfUseCase = EncryptPdfUseCase(dispatchers, pdfOps)

    @Provides
    fun provideDecryptPdfUseCase(
        dispatchers: com.propdf.core.domain.dispatcher.DispatcherProvider,
        pdfOps: PdfOperationsRepository
    ): DecryptPdfUseCase = DecryptPdfUseCase(dispatchers, pdfOps)

    @Provides
    fun provideExportAnnotationsUseCase(
        dispatchers: com.propdf.core.domain.dispatcher.DispatcherProvider,
        pdfOps: PdfOperationsRepository
    ): ExportAnnotationsUseCase = ExportAnnotationsUseCase(dispatchers, pdfOps)
}
