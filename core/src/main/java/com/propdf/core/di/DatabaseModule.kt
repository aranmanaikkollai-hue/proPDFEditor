package com.propdf.core.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.MIGRATION_2_3
import com.propdf.core.data.database.MIGRATION_3_4
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.database.dao.*
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideRecentFileDao(db: ProPDFDatabase) = db.recentFileDao()

    @Provides
    fun provideBookmarkDao(db: ProPDFDatabase) = db.bookmarkDao()

    @Provides
    fun provideOcrRecordDao(db: ProPDFDatabase) = db.ocrRecordDao()

    @Provides
    fun provideSignatureDao(db: ProPDFDatabase) = db.signatureDao()

    @Provides
    fun provideScanRecordDao(db: ProPDFDatabase) = db.scanRecordDao()

    @Provides
    fun provideCollectionDao(db: ProPDFDatabase) = db.collectionDao()

    @Provides
    fun provideReadingProgressDao(db: ProPDFDatabase) = db.readingProgressDao()

    @Provides
    fun provideRecycleBinDao(db: ProPDFDatabase) = db.recycleBinDao()
}
