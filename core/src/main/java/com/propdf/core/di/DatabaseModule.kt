package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.SearchDao
import com.propdf.core.data.database.SearchDatabase
import com.propdf.core.data.local.OcrDatabase
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.data.local.RecentFilesDao
import com.propdf.core.data.local.RecentFilesDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideSearchDatabase(
        @ApplicationContext context: Context
    ): SearchDatabase = Room.databaseBuilder(
        context,
        SearchDatabase::class.java,
        "search_database"
    ).build()

    @Provides
    fun provideSearchDao(database: SearchDatabase): SearchDao = database.searchDao()

    @Provides
    @Singleton
    fun provideRecentFilesDatabase(
        @ApplicationContext context: Context
    ): RecentFilesDatabase = Room.databaseBuilder(
        context,
        RecentFilesDatabase::class.java,
        "recent_files_database"
    ).build()

    @Provides
    fun provideRecentFilesDao(database: RecentFilesDatabase): RecentFilesDao = database.recentFilesDao()

    @Provides
    @Singleton
    fun provideOcrDatabase(
        @ApplicationContext context: Context
    ): OcrDatabase = Room.databaseBuilder(
        context,
        OcrDatabase::class.java,
        "ocr_database"
    ).build()

    @Provides
    fun provideOcrJobDao(database: OcrDatabase): OcrJobDao = database.ocrJobDao()
}
