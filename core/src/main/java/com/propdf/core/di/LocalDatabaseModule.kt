package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.SearchDatabase
import com.propdf.core.data.local.CompressionHistoryDao
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

// These three Room databases (RecentFilesDatabase, OcrDatabase, SearchDatabase)
// existed with real entities/DAOs and real consumers, but had no Hilt wiring
// anywhere in the project — nothing ever called Room.databaseBuilder() for
// them outside of SearchDatabase's own manual getInstance() singleton.
@Module
@InstallIn(SingletonComponent::class)
object LocalDatabaseModule {

    @Provides
    @Singleton
    fun provideRecentFilesDatabase(@ApplicationContext context: Context): RecentFilesDatabase {
        return Room.databaseBuilder(
            context,
            RecentFilesDatabase::class.java,
            "recent_files_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideRecentFilesDao(db: RecentFilesDatabase): RecentFilesDao = db.recentFileDao()

    @Provides
    fun provideCompressionHistoryDao(db: RecentFilesDatabase): CompressionHistoryDao =
        db.compressionHistoryDao()

    @Provides
    @Singleton
    fun provideOcrDatabase(@ApplicationContext context: Context): OcrDatabase {
        return Room.databaseBuilder(
            context,
            OcrDatabase::class.java,
            "ocr_jobs_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideOcrJobDao(db: OcrDatabase): OcrJobDao = db.ocrJobDao()

    @Provides
    @Singleton
    fun provideSearchDatabase(@ApplicationContext context: Context): SearchDatabase =
        SearchDatabase.getInstance(context)
}
