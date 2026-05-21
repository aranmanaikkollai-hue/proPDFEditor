package com.propdf.viewer.pdf.di

import android.content.Context
import com.propdf.viewer.pdf.PdfExportManager
import com.propdf.viewer.pdf.PdfToolEngine
import com.propdf.viewer.pdf.TempFileManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped

@Module
@InstallIn(ActivityComponent::class)
object PdfToolsModule {

    @Provides
    @ActivityScoped
    fun providePdfToolEngine(@ActivityContext context: Context): PdfToolEngine {
        return PdfToolEngine(context)
    }

    @Provides
    @ActivityScoped
    fun provideTempFileManager(@ActivityContext context: Context): TempFileManager {
        return TempFileManager(context)
    }

    @Provides
    @ActivityScoped
    fun providePdfExportManager(
        @ActivityContext context: Context,
        engine: PdfToolEngine,
        tempManager: TempFileManager
    ): PdfExportManager {
        return PdfExportManager(context, engine, tempManager)
    }
}
