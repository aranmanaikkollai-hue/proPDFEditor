package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.SearchDatabase
import com.propdf.core.data.local.CompressionHistoryDao
import com.propdf.core.data.local.OcrDatabase
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.data.local.RecentFilesDatabase
import com.propdf.core.data.local.RecentFilesDao
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
    fun provideDatabase(@ApplicationContext context: Context): RecentFilesDatabase {
        return Room.databaseBuilder(
            context,
            RecentFilesDatabase::class.java,
            "propdf_database"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideRecentFileDao(database: RecentFilesDatabase): RecentFilesDao {
        return database.recentFileDao()
    }

    @Provides
    fun provideCompressionHistoryDao(database: RecentFilesDatabase): CompressionHistoryDao {
        return database.compressionHistoryDao()
    }

    @Provides
    @Singleton
    fun provideOcrDatabase(@ApplicationContext context: Context): OcrDatabase {
        return Room.databaseBuilder(
            context,
            OcrDatabase::class.java,
            "ocr_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideOcrJobDao(database: OcrDatabase): OcrJobDao {
        return database.ocrJobDao()
    }

    @Provides
    @Singleton
    fun provideSearchDatabase(@ApplicationContext context: Context): SearchDatabase {
        return SearchDatabase.getInstance(context)
    }

    private val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) { database ->
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS compression_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sourceUri TEXT NOT NULL,
                outputUri TEXT NOT NULL,
                originalSizeBytes INTEGER NOT NULL,
                compressedSizeBytes INTEGER NOT NULL,
                compressionRatio REAL NOT NULL,
                config TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                fileName TEXT NOT NULL
            )
        """)
    }
}
