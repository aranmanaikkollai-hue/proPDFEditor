package com.propdf.editor.di

import android.content.Context
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.pool.BitmapPool
import com.propdf.editor.data.local.RecentFilesDao
import com.propdf.editor.data.local.RecentFilesDatabase
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

    @Provides
    @Singleton
    fun providePdfOperationsManager(
        @ApplicationContext context: Context
    ): PdfOperationsManager = PdfOperationsManager(context)

    @Provides
    @Singleton
    fun provideOcrManager(): OcrManager = OcrManager()

    @Provides
    @Singleton
    fun provideScannerProcessor(): ScannerProcessor = ScannerProcessor()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): RecentFilesDatabase = RecentFilesDatabase.get(context)

    @Provides
    @Singleton
    fun provideRecentFilesDao(
        db: RecentFilesDatabase
    ): RecentFilesDao = db.recentFilesDao()

    // ─── Optimized core components ────────────────────────────────

    @Provides
    @Singleton
    fun provideBitmapPool(): BitmapPool = BitmapPool.getDefaultInstance()

    @Provides
    @Singleton
    fun provideLruBitmapCache(
        @ApplicationContext context: Context
    ): LruBitmapCache = LruBitmapCache.getInstance(context)
}
