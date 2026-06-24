package com.propdf.core.di

import android.content.Context
import com.propdf.core.domain.dispatcher.DefaultDispatcherProvider
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.logger.DefaultAppLogger
import com.propdf.core.saf.SafEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    @Provides
    @Singleton
    fun provideSafEngine(
        @ApplicationContext context: Context
    ): SafEngine = SafEngine(context)
}
