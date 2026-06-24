package com.propdf.sync.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.propdf.sync.data.local.FolderStateDao
import com.propdf.sync.data.local.SyncDatabase
import com.propdf.sync.data.local.WatchedFolderDao
import com.propdf.sync.data.repository.FolderWatchRepositoryImpl
import com.propdf.sync.domain.repository.FolderWatchRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    abstract fun bindFolderWatchRepository(
        impl: FolderWatchRepositoryImpl
    ): FolderWatchRepository

    companion object {
        @Provides
        @Singleton
        fun provideSyncDatabase(
            @ApplicationContext context: Context
        ): SyncDatabase = Room.databaseBuilder(
            context,
            SyncDatabase::class.java,
            "sync_database"
        ).build()

        @Provides
        fun provideWatchedFolderDao(database: SyncDatabase): WatchedFolderDao = 
            database.watchedFolderDao()

        @Provides
        fun provideFolderStateDao(database: SyncDatabase): FolderStateDao = 
            database.folderStateDao()

        @Provides
        @Singleton
        fun provideWorkManager(
            @ApplicationContext context: Context
        ): WorkManager = WorkManager.getInstance(context)
    }
}
