package com.propdf.core.di

import com.propdf.core.domain.dispatcher.DefaultDispatcherProvider
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AndroidLogger
import com.propdf.core.domain.logger.AppLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): AppLogger
}
