package com.propdf.core.di

import com.propdf.core.data.local.CompressionHistoryDao
import com.propdf.core.data.repository.PdfBoxCompressionRepository
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.saf.SafHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CompressionModule {

    @Provides
    @Singleton
    fun provideCompressionRepository(
        @ApplicationContext context: android.content.Context,
        dispatchers: com.propdf.core.domain.dispatcher.DispatcherProvider,
        safHelper: SafHelper,
        historyDao: CompressionHistoryDao
    ): CompressionRepository = PdfBoxCompressionRepository(
        context,
        dispatchers,
        safHelper,
        historyDao
    )
}
