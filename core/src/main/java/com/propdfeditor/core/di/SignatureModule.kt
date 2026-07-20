package com.propdfeditor.core.di

import android.content.Context
import com.propdfeditor.core.database.AppDatabase
import com.propdfeditor.core.database.dao.CertificateDao
import com.propdfeditor.core.database.dao.SignatureDao
import com.propdfeditor.core.database.dao.SignatureHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SignatureModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideSignatureDao(database: AppDatabase): SignatureDao {
        return database.signatureDao()
    }

    @Provides
    @Singleton
    fun provideSignatureHistoryDao(database: AppDatabase): SignatureHistoryDao {
        return database.signatureHistoryDao()
    }

    @Provides
    @Singleton
    fun provideCertificateDao(database: AppDatabase): CertificateDao {
        return database.certificateDao()
    }
}
