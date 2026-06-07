package com.propdf.viewer.di

import android.content.Context
import com.propdf.core.data.database.SearchDatabase
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.search.SearchIndex
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for the viewer module.
 * Provides singleton instances of core rendering and search components.
 */
@Module
@InstallIn(SingletonComponent::class)
object ViewerModule {

    @Provides
    @Singleton
    fun provideBitmapPool(): BitmapPool {
        return BitmapPool.getInstance()
    }

    @Provides
    @Singleton
    fun provideSearchDatabase(@ApplicationContext context: Context): SearchDatabase {
        return SearchDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSearchIndex(
        @ApplicationContext context: Context,
        database: SearchDatabase
    ): SearchIndex {
        return SearchIndex(context, database)
    }
}
