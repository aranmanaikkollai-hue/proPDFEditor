package com.propdf.scanner.di

import android.content.Context
import com.propdf.scanner.engine.DocumentScannerEngine
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {

    @Provides
    @Singleton
    fun provideDocumentScannerEngine(@ApplicationContext context: Context): DocumentScannerEngine {
        return DocumentScannerEngine(context)
    }

    @Provides
    @Singleton
    fun provideMlKitOcrEngine(@ApplicationContext context: Context): MlKitOcrEngine {
        return MlKitOcrEngine(context)
    }

    @Provides
    @Singleton
    fun provideSearchablePdfGenerator(@ApplicationContext context: Context): SearchablePdfGenerator {
        return SearchablePdfGenerator(context)
    }
}
