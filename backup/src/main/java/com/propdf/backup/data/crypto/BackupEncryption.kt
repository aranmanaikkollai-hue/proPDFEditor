package com.propdf.backup.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encryption layer for backups using Android Keystore + AES-256-GCM.
 */
@Singleton
class BackupEncryption @Inject constructor(
    private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Encrypt data and return encrypted bytes.
     */
    fun encrypt(data: ByteArray): AppResult<ByteArray> {
        return try {
            val tempFile = File.createTempFile("backup_enc", ".tmp", context.cacheDir)
            val encryptedFile = EncryptedFile.Builder(
                context,
                tempFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileOutput().use { output ->
                output.write(data)
            }

            val encrypted = tempFile.readBytes()
            tempFile.delete()
            AppResult.Success(encrypted)
        } catch (e: Exception) {
            AppResult.Error(AppException.SecurityError("Encryption failed: ${e.message}"))
        }
    }

    /**
     * Decrypt encrypted data.
     */
    fun decrypt(encryptedData: ByteArray): AppResult<ByteArray> {
        return try {
            val tempFile = File.createTempFile("backup_dec", ".tmp", context.cacheDir)
            tempFile.writeBytes(encryptedData)

            val encryptedFile = EncryptedFile.Builder(
                context,
                tempFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            val decrypted = encryptedFile.openFileInput().use { input ->
                input.readBytes()
            }
            tempFile.delete()
            AppResult.Success(decrypted)
        } catch (e: Exception) {
            AppResult.Error(AppException.SecurityError("Decryption failed: ${e.message}"))
        }
    }

    /**
     * Calculate SHA-256 checksum.
     */
    fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
