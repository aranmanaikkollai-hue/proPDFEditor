package com.propdf.viewer.pdf.di

import android.content.Context
import com.propdf.viewer.pdf.PdfExportManager
import com.propdf.viewer.pdf.PdfToolEngine
import com.propdf.viewer.pdf.TempFileManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PdfToolsModule {

    @Provides
    @Singleton
    fun providePdfToolEngine(@ApplicationContext context: Context): PdfToolEngine {
        return PdfToolEngine(context)
    }

    @Provides
    @Singleton
    fun provideTempFileManager(@ApplicationContext context: Context): TempFileManager {
        return TempFileManager(context)
    }

    @Provides
    @Singleton
    fun providePdfExportManager(
        @ApplicationContext context: Context,
        engine: PdfToolEngine,
        tempManager: TempFileManager
    ): PdfExportManager {
        return PdfExportManager(context, engine, tempManager)
    }
}
