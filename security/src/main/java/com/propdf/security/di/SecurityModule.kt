package com.propdf.security.di

import com.propdf.security.encryption.EncryptionManager
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine
import com.propdf.security.redaction.RedactionEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideSignatureManager(): SignatureManager = SignatureManager()

    @Provides
    @Singleton
    fun provideWatermarkEngine(): WatermarkEngine = WatermarkEngine()

    @Provides
    @Singleton
    fun provideRedactionEngine(): RedactionEngine = RedactionEngine()
}
