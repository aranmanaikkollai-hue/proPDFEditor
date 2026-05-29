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

/**
 * Hilt dependency module for security module.
 * Provides SignatureManager, EncryptionManager, WatermarkEngine, and RedactionEngine.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideSignatureManager(
        @ApplicationContext context: Context
    ): SignatureManager {
        return SignatureManager(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideEncryptionManager(
        @ApplicationContext context: Context
    ): EncryptionManager {
        return EncryptionManager(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideWatermarkEngine(
        @ApplicationContext context: Context
    ): WatermarkEngine {
        return WatermarkEngine(context.applicationContext)
    }

    @Provides
    @Singleton
    fun provideRedactionEngine(
        @ApplicationContext context: Context
    ): RedactionEngine {
        return RedactionEngine(context.applicationContext)
    }
}
