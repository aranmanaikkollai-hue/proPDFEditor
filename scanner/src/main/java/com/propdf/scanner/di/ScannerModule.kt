package com.propdf.scanner.di

import android.content.Context
import androidx.room.Room
import com.propdf.scanner.data.local.ScannerDatabase
import com.propdf.scanner.data.local.ScannedDocumentDao
import com.propdf.scanner.data.repository.ScannerRepository
import com.propdf.scanner.data.repository.ScannerRepositoryImpl
import com.propdf.scanner.processing.*
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
