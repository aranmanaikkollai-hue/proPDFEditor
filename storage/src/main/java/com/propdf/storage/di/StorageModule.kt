package com.propdf.storage.di

import android.content.Context
import androidx.room.Room
import com.propdf.storage.data.local.PersistedUriDao
import com.propdf.storage.data.local.StorageDatabase
import com.propdf.storage.data.repository.SafRepositoryImpl
import com.propdf.storage.domain.repository.SafRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    abstract fun bindSafRepository(
        impl: SafRepositoryImpl
    ): SafRepository

    companion object {
        @Provides
        @Singleton
        fun provideStorageDatabase(
            @ApplicationContext context: Context
        ): StorageDatabase = Room.databaseBuilder(
            context,
            StorageDatabase::class.java,
            "storage_database"
        ).build()

        @Provides
        fun providePersistedUriDao(database: StorageDatabase): PersistedUriDao = 
            database.persistedUriDao()
    }
}
