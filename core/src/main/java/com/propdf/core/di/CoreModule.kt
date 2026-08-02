package com.propdf.core.di

import android.content.Context
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.dispatcher.DispatcherProviderImpl
import com.propdf.core.domain.logger.ProPDFLogger
import com.propdf.core.domain.logger.TimberLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DispatcherProviderImpl()

    @Provides
    @Singleton
    fun provideLogger(): ProPDFLogger = TimberLogger()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        dispatcherProvider: DispatcherProvider
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
        @Provides
    @Singleton
    fun provideSignaturesDir(@ApplicationContext context: Context): File {
        return File(context.filesDir, "signatures").apply { mkdirs() }
    }
}
