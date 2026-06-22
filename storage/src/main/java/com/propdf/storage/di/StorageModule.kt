package com.propdf.storage.di

import android.content.Context
import com.propdf.storage.data.local.PersistedUriDao
import com.propdf.storage.data.local.StorageDatabase
import com.propdf.storage.data.repository.SafRepositoryImpl
import com.propdf.storage.domain.repository.SafRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideStorageDatabase(@ApplicationContext context: Context): StorageDatabase {
        return StorageDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun providePersistedUriDao(database: StorageDatabase): PersistedUriDao {
        return database.persistedUriDao()
    }

    @Provides
    @Singleton
    fun provideSafRepository(
        @ApplicationContext context: Context,
        persistedUriDao: PersistedUriDao,
        dispatcherProvider: com.propdf.core.domain.dispatcher.DispatcherProvider
    ): SafRepository {
        return SafRepositoryImpl(context, persistedUriDao, dispatcherProvider)
    }
}
