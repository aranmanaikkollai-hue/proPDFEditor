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

@Module
@InstallIn(ActivityComponent::class)
object PdfToolsModule {

    @Provides
    fun providePdfToolEngine(@ActivityContext context: Context): PdfToolEngine {
        return PdfToolEngine(context)
    }

    @Provides
    fun provideTempFileManager(@ActivityContext context: Context): TempFileManager {
        return TempFileManager(context)
    }

    @Provides
    fun providePdfExportManager(
        @ActivityContext context: Context,
        engine: PdfToolEngine,
        tempManager: TempFileManager
    ): PdfExportManager {
        return PdfExportManager(context, engine, tempManager)
    }
}
