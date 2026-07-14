package com.propdfeditor.core.di

import android.content.Context
import com.propdfeditor.core.database.AppDatabase
import com.propdfeditor.core.database.dao.CertificateDao
import com.propdfeditor.core.database.dao.SignatureDao
import com.propdfeditor.core.database.dao.SignatureHistoryDao
import com.propdfeditor.core.pdf.signature.PdfSignatureEngine
import com.propdfeditor.core.repository.CertificateRepository
import com.propdfeditor.core.repository.SignatureHistoryRepository
import com.propdfeditor.core.repository.SignatureRepository
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

    @Provides
    @Singleton
    fun provideSignatureRepository(
        @ApplicationContext context: Context,
        signatureDao: SignatureDao
    ): SignatureRepository {
        return SignatureRepository(context, signatureDao)
    }

    @Provides
    @Singleton
    fun provideSignatureHistoryRepository(
        historyDao: SignatureHistoryDao
    ): SignatureHistoryRepository {
        return SignatureHistoryRepository(historyDao)
    }

    @Provides
    @Singleton
    fun provideCertificateRepository(
        @ApplicationContext context: Context,
        certificateDao: CertificateDao
    ): CertificateRepository {
        return CertificateRepository(context, certificateDao)
    }

    @Provides
    @Singleton
    fun providePdfSignatureEngine(
        @ApplicationContext context: Context
    ): PdfSignatureEngine {
        return PdfSignatureEngine(context)
    }
}
