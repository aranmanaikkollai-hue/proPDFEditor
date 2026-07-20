package com.propdfeditor.batch.di

import android.content.Context
import com.propdfeditor.batch.data.database.BatchDatabase
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.scheduler.BatchWorkScheduler
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.propdfeditor.batch.worker.BatchWorkerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BatchModule {

    @Provides
    @Singleton
    fun provideBatchDatabase(@ApplicationContext context: Context): BatchDatabase {
        return BatchDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideBatchJobRepository(
        @ApplicationContext context: Context,
        database: BatchDatabase
    ): BatchJobRepository {
        return BatchJobRepository(context, database)
    }

    @Provides
    @Singleton
    fun provideBatchNotificationManager(
        @ApplicationContext context: Context
    ): BatchNotificationManager {
        return BatchNotificationManager(context)
    }

    @Provides
    @Singleton
    fun providePdfProcessor(): PdfProcessor {
        return PdfProcessor()
    }

    @Provides
    @Singleton
    fun provideBatchWorkScheduler(
        @ApplicationContext context: Context,
        repository: BatchJobRepository
    ): BatchWorkScheduler {
        return BatchWorkScheduler(context, repository)
    }

    @Provides
    @Singleton
    fun provideBatchWorkerFactory(
        repository: BatchJobRepository,
        notificationManager: BatchNotificationManager,
        pdfProcessor: PdfProcessor
    ): BatchWorkerFactory {
        return BatchWorkerFactory(repository, notificationManager, pdfProcessor)
    }
}
