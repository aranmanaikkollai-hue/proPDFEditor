package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.local.CompressionHistoryDao
import com.propdf.core.data.local.ProPDFDatabase
import com.propdf.core.data.local.RecentFileDao
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
    fun provideDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(
            context,
            ProPDFDatabase::class.java,
            "propdf_database"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideRecentFileDao(database: ProPDFDatabase): RecentFileDao {
        return database.recentFileDao()
    }

    @Provides
    fun provideCompressionHistoryDao(database: ProPDFDatabase): CompressionHistoryDao {
        return database.compressionHistoryDao()
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
