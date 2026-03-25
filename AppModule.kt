// PATH: app/src/main/java/com/propdf/editor/di/AppModule.kt
package com.propdf.editor.di

import android.content.Context
import com.propdf.editor.data.local.RecentFilesDatabase
import com.propdf.editor.data.local.RecentFilesDao
import com.propdf.editor.data.repository.OcrManager
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.data.repository.ScannerProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun providePdfOperationsManager(
        @ApplicationContext context: Context
    ): PdfOperationsManager = PdfOperationsManager(context)

    @Provides @Singleton
    fun provideOcrManager(
        @ApplicationContext context: Context
    ): OcrManager = OcrManager(context)

    @Provides @Singleton
    fun provideScannerProcessor(): ScannerProcessor = ScannerProcessor()

    @Provides @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): RecentFilesDatabase = RecentFilesDatabase.get(context)

    @Provides @Singleton
    fun provideRecentFilesDao(
        db: RecentFilesDatabase
    ): RecentFilesDao = db.recentFilesDao()
}
