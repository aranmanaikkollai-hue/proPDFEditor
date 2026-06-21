package com.propdfeditor.scanner.di

import com.propdfeditor.scanner.data.processing.OpenCvDocumentProcessor
import com.propdfeditor.scanner.data.repository.DispatcherProvider
import com.propdfeditor.scanner.data.repository.ScannerRepositoryImpl
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the scanner feature.
 * Provides OpenCV processor, dispatcher provider, and binds repository.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {

    @Binds
    @Singleton
    abstract fun bindScannerRepository(
        impl: ScannerRepositoryImpl
    ): ScannerRepository

    companion object {
        @Provides
        @Singleton
        fun provideOpenCvDocumentProcessor(): OpenCvDocumentProcessor {
            return OpenCvDocumentProcessor()
        }

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider {
            return object : DispatcherProvider {}
        }
    }
}
