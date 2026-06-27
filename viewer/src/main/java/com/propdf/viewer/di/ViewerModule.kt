package com.propdf.viewer.di

import com.propdf.core.data.database.SearchDatabase
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.search.SearchIndex
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for the viewer module.
 */
@Module
@InstallIn(SingletonComponent::class)
object ViewerModule {

    @Provides
    @Singleton
    fun provideBitmapPool(): BitmapPool {
        return BitmapPool()
    }

    // provideSearchDatabase removed: already provided by core/di/DatabaseModule

    @Provides
    @Singleton
    fun provideSearchIndex(
        @ApplicationContext context: Context,
        database: SearchDatabase
    ): SearchIndex {
        return SearchIndex(context, database)
    }
}
