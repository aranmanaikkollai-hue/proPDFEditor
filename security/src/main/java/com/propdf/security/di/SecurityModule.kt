package com.propdf.security.di

import android.content.Context
import com.propdf.security.encryption.EncryptionManager
import com.propdf.security.redaction.RedactionEngine
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine

/**
 * Manual dependency provider for security module.
 */
object SecurityModule {

    @Volatile
    private var signatureManager: SignatureManager? = null

    @Volatile
    private var encryptionManager: EncryptionManager? = null

    @Volatile
    private var watermarkEngine: WatermarkEngine? = null

    @Volatile
    private var redactionEngine: RedactionEngine? = null

    fun provideSignatureManager(context: Context): SignatureManager {
        return signatureManager ?: synchronized(this) {
            signatureManager ?: SignatureManager(context.applicationContext).also {
                signatureManager = it
            }
        }
    }

    fun provideEncryptionManager(context: Context): EncryptionManager {
        return encryptionManager ?: synchronized(this) {
            encryptionManager ?: EncryptionManager(context.applicationContext).also {
                encryptionManager = it
            }
        }
    }

    fun provideWatermarkEngine(context: Context): WatermarkEngine {
        return watermarkEngine ?: synchronized(this) {
            watermarkEngine ?: WatermarkEngine(context.applicationContext).also {
                watermarkEngine = it
            }
        }
    }

    fun provideRedactionEngine(context: Context): RedactionEngine {
        return redactionEngine ?: synchronized(this) {
            redactionEngine ?: RedactionEngine(context.applicationContext).also {
                redactionEngine = it
            }
        }
    }

    fun release() {
        signatureManager = null
        encryptionManager = null
        watermarkEngine = null
        redactionEngine = null
    }
}
