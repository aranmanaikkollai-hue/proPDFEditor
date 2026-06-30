
package com.propdf.viewer.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.SearchDatabase
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
import com.propdf.viewer.data.database.BookmarkDao
import com.propdf.viewer.data.database.RecentPageDao
import com.propdf.viewer.data.database.ViewerDatabase
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.search.SearchIndex
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewerModule {

    @Provides
    @Singleton
    fun provideViewerDatabase(@ApplicationContext context: Context): ViewerDatabase {
        return Room.databaseBuilder(
            context,
            ViewerDatabase::class.java,
            "viewer_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideBookmarkDao(database: ViewerDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    @Singleton
    fun provideRecentPageDao(database: ViewerDatabase): RecentPageDao = database.recentPageDao()

    @Provides
    @Singleton
    fun providePageCacheManager(@ApplicationContext context: Context): PageCacheManager {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val isLowRam = activityManager.isLowRamDevice
        return PageCacheManager(
            context = context,
            maxMemoryMB = if (isLowRam) 48 else 128,
            lowRamMode = isLowRam
        )
    }

    @Provides
    @Singleton
    fun provideBitmapPool(): BitmapPool = BitmapPool.getInstance()

    @Provides
    @Singleton
    fun provideSearchDatabase(@ApplicationContext context: Context): SearchDatabase =
        SearchDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideSearchIndex(
        @ApplicationContext context: Context,
        database: SearchDatabase
    ): SearchIndex = SearchIndex(context, database)
}
