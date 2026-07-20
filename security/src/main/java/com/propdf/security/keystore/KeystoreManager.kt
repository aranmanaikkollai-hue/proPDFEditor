package com.propdf.security.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise-grade keystore manager using Android Keystore for key protection.
 */
@Singleton
class KeystoreManager @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "propdf_master_key_v1"
        private const val BIOMETRIC_KEY_ALIAS = "propdf_biometric_key_v1"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val KEY_SIZE = 256
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // =======================================================================
    // MASTER KEY
    // =======================================================================

    suspend fun getOrCreateMasterKey(): SecretKey = withContext(Dispatchers.IO) {
        keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
            ?: generateMasterKey()
    }

    private fun generateMasterKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    // =======================================================================
    // BIOMETRIC-BOUND KEY
    // =======================================================================

    suspend fun getOrCreateBiometricKey(): SecretKey = withContext(Dispatchers.IO) {
        keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
            ?: generateBiometricKey()
    }

    private fun generateBiometricKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(-1)
            .setInvalidatedByBiometricEnrollment(true)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        keyGen.init(specBuilder.build())
        return keyGen.generateKey()
    }

    suspend fun invalidateBiometricKey() = withContext(Dispatchers.IO) {
        try {
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (_: Exception) {
            // Entry may not exist
        }
    }

    suspend fun hasBiometricKey(): Boolean = withContext(Dispatchers.IO) {
        keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)
    }

    // =======================================================================
    // AES-256-GCM ENCRYPTION / DECRYPTION
    // =======================================================================

    suspend fun encrypt(plaintext: ByteArray, key: SecretKey): String = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        val combined = ByteArray(GCM_IV_LENGTH + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH)
        System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.size)

        Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    suspend fun decrypt(encryptedBase64: String, key: SecretKey): ByteArray = withContext(Dispatchers.IO) {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Invalid ciphertext: too short" }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.doFinal(ciphertext)
    }

    // =======================================================================
    // FILE ENCRYPTION (chunked for large PDFs)
    // =======================================================================

    suspend fun encryptFile(
        inputFile: java.io.File,
        outputFile: java.io.File,
        key: SecretKey,
        chunkSize: Int = 64 * 1024
    ) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        outputFile.outputStream().use { outStream ->
            outStream.write(cipher.iv)
            javax.crypto.CipherOutputStream(outStream, cipher).use { cipherOut ->
                inputFile.inputStream().use { inStream ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    while (inStream.read(buffer).also { bytesRead = it } != -1) {
                        cipherOut.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    suspend fun decryptFile(
        inputFile: java.io.File,
        outputFile: java.io.File,
        key: SecretKey,
        chunkSize: Int = 64 * 1024
    ) = withContext(Dispatchers.IO) {
        inputFile.inputStream().use { inStream ->
            val iv = ByteArray(GCM_IV_LENGTH)
            val ivRead = inStream.read(iv)
            require(ivRead == GCM_IV_LENGTH) { "Invalid encrypted file: missing IV" }

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

            outputFile.outputStream().use { outStream ->
                javax.crypto.CipherInputStream(inStream, cipher).use { cipherIn ->
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    while (cipherIn.read(buffer).also { bytesRead = it } != -1) {
                        outStream.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    // =======================================================================
    // PASSWORD-BASED KEY DERIVATION (for backup encryption)
    // =======================================================================

    suspend fun deriveKeyFromPassword(
        password: CharArray,
        salt: ByteArray? = null
    ): Pair<SecretKey, ByteArray> = withContext(Dispatchers.IO) {
        val actualSalt = salt ?: ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, actualSalt, 100_000, KEY_SIZE)
        val tmp = factory.generateSecret(spec)
        val key = SecretKeySpec(tmp.encoded, "AES")
        spec.clearPassword()
        key to actualSalt
    }

    suspend fun encryptWithPassword(plaintext: ByteArray, password: CharArray): String = withContext(Dispatchers.IO) {
        val (key, salt) = deriveKeyFromPassword(password)
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        val combined = ByteArray(salt.size + iv.size + ciphertext.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(ciphertext, 0, combined, salt.size + iv.size, ciphertext.size)

        Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    suspend fun decryptWithPassword(encryptedBase64: String, password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        require(combined.size > 16 + GCM_IV_LENGTH) { "Invalid encrypted data" }

        val salt = combined.copyOfRange(0, 16)
        val iv = combined.copyOfRange(16, 16 + GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(16 + GCM_IV_LENGTH, combined.size)

        val (key, _) = deriveKeyFromPassword(password, salt)
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.doFinal(ciphertext)
    }

    // =======================================================================
    // SECURE MEMORY HELPERS
    // =======================================================================

    inline fun <R> withPasswordBytes(password: CharArray, block: (ByteArray) -> R): R {
        val bytes = CharBuffer.wrap(password).let { cb ->
            StandardCharsets.UTF_8.encode(cb).array()
        }
        return try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun clearBytes(bytes: ByteArray) {
        bytes.fill(0)
    }
}
