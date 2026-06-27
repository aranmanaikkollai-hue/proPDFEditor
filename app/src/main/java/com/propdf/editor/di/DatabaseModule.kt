package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.editor.data.local.db.AppDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "propdf_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePdfDocumentDao(db: AppDatabase) = db.pdfDocumentDao()

    @Provides
    fun provideFolderDao(db: AppDatabase) = db.folderDao()

    @Provides
    fun provideCloudAccountDao(db: AppDatabase) = db.cloudAccountDao()

    @Provides
    fun provideSearchIndexDao(db: AppDatabase) = db.searchIndexDao()

    @Provides
    fun provideCollectionDao(db: AppDatabase) = db.collectionDao()

    @Provides
    fun provideTagDao(db: AppDatabase) = db.tagDao()

    @Provides
    fun provideFileHashDao(db: AppDatabase) = db.fileHashDao()
}
