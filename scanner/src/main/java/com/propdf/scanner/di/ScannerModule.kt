package com.propdf.scanner.di

import android.content.Context
import androidx.room.Room
import com.propdf.scanner.data.local.ScannerDatabase
import com.propdf.scanner.data.local.ScannedDocumentDao
import com.propdf.scanner.data.repository.ScannerRepository
import com.propdf.scanner.data.repository.ScannerRepositoryImpl
import com.propdf.scanner.processing.*
import com.propdfeditor.scanner.data.processing.ScannerProcessingRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerBindingsModule {
    @Binds @Singleton abstract fun bindScannerRepository(impl: ScannerRepositoryImpl): ScannerRepository
}

// NOTE: this binds a DIFFERENT interface than ScannerBindingsModule above -
// com.propdfeditor.scanner.domain.repository.ScannerRepository (image-processing
// contract used by ScannerViewModel's use cases) vs com.propdf.scanner.data.repository.
// ScannerRepository (document/page persistence contract) bound above. They share a
// simple name but are unrelated interfaces.
@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerDomainBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDomainScannerRepository(
        impl: ScannerProcessingRepositoryImpl
    ): com.propdfeditor.scanner.domain.repository.ScannerRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ScannerProvidesModule {
    @Provides @Singleton
    fun provideScannerDatabase(@ApplicationContext context: Context): ScannerDatabase =
        Room.databaseBuilder(context, ScannerDatabase::class.java, "scanner_database.db")
            .fallbackToDestructiveMigration().build()

    @Provides @Singleton fun provideScannedDocumentDao(db: ScannerDatabase): ScannedDocumentDao = db.scannedDocumentDao()
    @Provides @Singleton fun provideEdgeDetector(): EdgeDetector = EdgeDetector()
    @Provides @Singleton fun providePerspectiveCorrector(): PerspectiveCorrector = PerspectiveCorrector()
    @Provides @Singleton fun provideImageEnhancer(): ImageEnhancer = ImageEnhancer()
    @Provides @Singleton fun provideScanModeDetector(): ScanModeDetector = ScanModeDetector()
    @Provides @Singleton fun providePdfCreator(@ApplicationContext ctx: Context): PdfCreator = PdfCreator(ctx)
    @Provides @Singleton fun provideBatchProcessor(@ApplicationContext ctx: Context): BatchProcessor = BatchProcessor(ctx)
}
