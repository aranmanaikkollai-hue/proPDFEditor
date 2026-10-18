package com.propdfeditor.batch.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore (minSdk 23 supports this).
 *
 * Key management:
 *  - One key per app install, alias [alias], created lazily on first [encrypt].
 *  - No user-authentication requirement: batch workers run in the background, so a key that
 *    needs the lock screen would make queued jobs unusable.
 *  - Keystore keys are not part of Auto Backup / device transfer. After a restore on another
 *    device the key is missing, [decrypt] throws KEY_UNAVAILABLE and the job needs the password
 *    again. This is intended.
 *  - [decrypt] never creates a key: a missing key is a failure, not a reason to mint a new one.
 *
 * This protects secrets at rest in the database and in backups. It does not protect them from
 * code running inside the app process or from a fully compromised device.
 */
class AndroidKeystoreBatchSecretCipher(
    private val alias: String = DEFAULT_ALIAS
) : BatchSecretCipher {

    private val lock = Any()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        try {
            val key = synchronized(lock) { loadKey(createIfMissing = true) }
                ?: throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key) // Keystore generates a random IV (randomized encryption required)
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_LENGTH_BYTES) {
                throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE)
            }
            return iv + ciphertext
        } catch (e: BatchSecretUnavailableException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE, e)
        } catch (e: ProviderException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE, e)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size <= IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.CORRUPTED)
        }
        try {
            val key = synchronized(lock) { loadKey(createIfMissing = false) }
                ?: throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE)
            val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BYTES * 8, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        } catch (e: BatchSecretUnavailableException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.CORRUPTED, e)
        } catch (e: BadPaddingException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.CORRUPTED, e)
        } catch (e: GeneralSecurityException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE, e)
        } catch (e: ProviderException) {
            throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.KEY_UNAVAILABLE, e)
        }
    }

    private fun loadKey(createIfMissing: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) return null

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "propdf_batch_job_secret_v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BYTES = 16
    }
}
