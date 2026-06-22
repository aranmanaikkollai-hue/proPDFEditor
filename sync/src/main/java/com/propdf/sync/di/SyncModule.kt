package com.propdf.sync.di

import android.content.Context
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.storage.domain.repository.SafRepository
import com.propdf.sync.data.local.FolderStateDao
import com.propdf.sync.data.local.SyncDatabase
import com.propdf.sync.data.local.WatchedFolderDao
import com.propdf.sync.data.repository.FolderWatchRepositoryImpl
import com.propdf.sync.domain.repository.FolderWatchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncDatabase(@ApplicationContext context: Context): SyncDatabase {
        return SyncDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideWatchedFolderDao(database: SyncDatabase): WatchedFolderDao {
        return database.watchedFolderDao()
    }

    @Provides
    @Singleton
    fun provideFolderStateDao(database: SyncDatabase): FolderStateDao {
        return database.folderStateDao()
    }

    @Provides
    @Singleton
    fun provideFolderWatchRepository(
        @ApplicationContext context: Context,
        safRepository: SafRepository,
        watchedFolderDao: WatchedFolderDao,
        folderStateDao: FolderStateDao,
        dispatcherProvider: DispatcherProvider
    ): FolderWatchRepository {
        return FolderWatchRepositoryImpl(
            context,
            safRepository,
            watchedFolderDao,
            folderStateDao,
            dispatcherProvider
        )
    }
}
