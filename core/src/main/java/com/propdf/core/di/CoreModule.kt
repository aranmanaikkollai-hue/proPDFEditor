package com.propdf.core.di

import com.propdf.core.domain.dispatcher.DefaultDispatcherProvider
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.logger.DefaultAppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger = DefaultAppLogger()
}
