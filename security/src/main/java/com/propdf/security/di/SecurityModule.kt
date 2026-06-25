package com.propdf.security.di

import android.content.Context
import com.propdf.security.encryption.EncryptionManager
import com.propdf.security.redaction.RedactionEngine
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideEncryptionManager(): EncryptionManager = EncryptionManager()

    @Provides
    @Singleton
    fun provideSignatureManager(
        @ApplicationContext context: Context
    ): SignatureManager = SignatureManager(context)

    @Provides
    @Singleton
    fun provideWatermarkEngine(
        @ApplicationContext context: Context
    ): WatermarkEngine = WatermarkEngine(context)

    @Provides
    @Singleton
    fun provideRedactionEngine(
        @ApplicationContext context: Context
    ): RedactionEngine = RedactionEngine(context)
}
