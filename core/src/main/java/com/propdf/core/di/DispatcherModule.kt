package com.propdf.core.di

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.dispatcher.DispatcherProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatcherModule {
    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DispatcherProviderImpl): DispatcherProvider
}
