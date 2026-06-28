package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.editor.data.local.dao.CloudAccountDao
import com.propdf.editor.data.local.dao.CollectionDao
import com.propdf.editor.data.local.dao.FileHashDao
import com.propdf.editor.data.local.dao.FolderDao
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.data.local.dao.TagDao
import com.propdf.editor.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// NOTE: RecentFilesDatabase/RecentFilesDao are provided by :core's DatabaseModule — NOT here.
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "app_database"
    )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun providePdfDocumentDao(db: AppDatabase): PdfDocumentDao = db.pdfDocumentDao()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideCloudAccountDao(db: AppDatabase): CloudAccountDao = db.cloudAccountDao()

    @Provides
    fun provideSearchIndexDao(db: AppDatabase): SearchIndexDao = db.searchIndexDao()

    @Provides
    fun provideCollectionDao(db: AppDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideFileHashDao(db: AppDatabase): FileHashDao = db.fileHashDao()
}
