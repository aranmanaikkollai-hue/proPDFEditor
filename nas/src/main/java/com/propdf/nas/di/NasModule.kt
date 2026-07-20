package com.propdf.nas.di

import android.content.Context
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.nas.data.local.NasConfigDao
import com.propdf.nas.data.local.NasDatabase
import com.propdf.nas.data.local.PendingOperationDao
import com.propdf.nas.data.repository.NasRepositoryImpl
import com.propdf.nas.data.smb.SmbClient
import com.propdf.nas.data.webdav.WebDavClient
import com.propdf.nas.domain.repository.NasRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NasModule {

    @Provides
    @Singleton
    fun provideNasDatabase(@ApplicationContext context: Context): NasDatabase {
        return NasDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideNasConfigDao(database: NasDatabase): NasConfigDao {
        return database.nasConfigDao()
    }

    @Provides
    @Singleton
    fun providePendingOperationDao(database: NasDatabase): PendingOperationDao {
        return database.pendingOperationDao()
    }

    @Provides
    @Singleton
    fun provideNasRepository(
        @ApplicationContext context: Context,
        webDavClient: WebDavClient,
        smbClient: SmbClient,
        dispatcherProvider: DispatcherProvider
    ): NasRepository {
        return NasRepositoryImpl(context, webDavClient, smbClient, dispatcherProvider)
    }
}
