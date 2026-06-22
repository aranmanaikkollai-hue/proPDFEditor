package com.propdf.backup.di

import android.content.Context
import com.propdf.backup.data.crypto.BackupEncryption
import com.propdf.backup.data.repository.BackupRepositoryImpl
import com.propdf.backup.domain.repository.BackupRepository
import com.propdf.core.domain.dispatcher.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    @Singleton
    fun provideBackupEncryption(@ApplicationContext context: Context): BackupEncryption {
        return BackupEncryption(context)
    }

    @Provides
    @Singleton
    fun provideBackupRepository(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider,
        encryption: BackupEncryption
    ): BackupRepository {
        return BackupRepositoryImpl(context, dispatcherProvider, encryption)
    }
}
