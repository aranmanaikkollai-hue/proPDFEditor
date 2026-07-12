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

@Module
@InstallIn(SingletonComponent::class)
object AppDatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "propdf_app_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePdfDocumentDao(database: AppDatabase): PdfDocumentDao = database.pdfDocumentDao()

    @Provides
    fun provideFolderDao(database: AppDatabase): FolderDao = database.folderDao()

    @Provides
    fun provideCloudAccountDao(database: AppDatabase): CloudAccountDao = database.cloudAccountDao()

    @Provides
    fun provideSearchIndexDao(database: AppDatabase): SearchIndexDao = database.searchIndexDao()

    @Provides
    fun provideCollectionDao(database: AppDatabase): CollectionDao = database.collectionDao()

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()

    @Provides
    fun provideFileHashDao(database: AppDatabase): FileHashDao = database.fileHashDao()
}
