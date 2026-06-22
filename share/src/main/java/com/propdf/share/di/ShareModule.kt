package com.propdf.share.di

import android.content.Context
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.share.data.lan.LanShareServer
import com.propdf.share.data.nearby.NearbyShareManager
import com.propdf.share.data.qr.QrCodeGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ShareModule {

    @Provides
    @Singleton
    fun provideNearbyShareManager(
        @ApplicationContext context: Context
    ): NearbyShareManager {
        return NearbyShareManager(context)
    }

    @Provides
    @Singleton
    fun provideLanShareServer(
        @ApplicationContext context: Context,
        dispatcherProvider: DispatcherProvider
    ): LanShareServer {
        return LanShareServer(context, dispatcherProvider)
    }

    @Provides
    @Singleton
    fun provideQrCodeGenerator(): QrCodeGenerator {
        return QrCodeGenerator()
    }
}
