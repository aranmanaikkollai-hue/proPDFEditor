package com.propdf.security.di

import android.content.Context
import com.propdf.security.backup.EncryptedBackupEngine
import com.propdf.security.biometric.BiometricAuthManager
import com.propdf.security.encryption.EncryptionManager
import com.propdf.security.keystore.KeystoreManager
import com.propdf.security.redaction.RedactionEngine
import com.propdf.security.session.SessionManager
import com.propdf.security.signature.SignatureManager
import com.propdf.security.vault.VaultEncryptionEngine
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

    // ── Existing providers (preserved) ─────────────────────────────────────

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

    // ── Phase 8: Keystore & Crypto ─────────────────────────────────────────

    @Provides
    @Singleton
    fun provideKeystoreManager(
        @ApplicationContext context: Context
    ): KeystoreManager = KeystoreManager(context)

    // ── Phase 8: Biometric Authentication ──────────────────────────────────

    @Provides
    @Singleton
    fun provideBiometricAuthManager(
        @ApplicationContext context: Context
    ): BiometricAuthManager = BiometricAuthManager(context)

    // ── Phase 8: Vault Encryption ──────────────────────────────────────────

    @Provides
    @Singleton
    fun provideVaultEncryptionEngine(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager
    ): VaultEncryptionEngine = VaultEncryptionEngine(context, keystoreManager)

    // ── Phase 8: Encrypted Backups ─────────────────────────────────────────

    @Provides
    @Singleton
    fun provideEncryptedBackupEngine(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager
    ): EncryptedBackupEngine = EncryptedBackupEngine(context, keystoreManager)

    // ── Phase 8: Session Management ────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context
    ): SessionManager = SessionManager(context)
}
