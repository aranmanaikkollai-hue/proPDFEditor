package com.propdfeditor.batch.security

/**
 * Authenticated encryption of small secrets (batch-job passwords).
 *
 * The production implementation is [AndroidKeystoreBatchSecretCipher]. The interface exists so the
 * envelope logic in [BatchJobSecrets] can be unit-tested on the JVM, where the Android Keystore is
 * not available.
 *
 * Implementations MUST:
 *  - use authenticated encryption (a tampered ciphertext or wrong [aad] must fail, never decrypt),
 *  - generate a fresh random IV for every [encrypt] call,
 *  - report every failure as [BatchSecretUnavailableException] without echoing any secret.
 */
interface BatchSecretCipher {
    /** Returns `iv || ciphertext || tag`. */
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Inverse of [encrypt]. Throws [BatchSecretUnavailableException] on any failure. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * A protected secret cannot be produced or recovered. The job must be re-created with the password
 * re-entered; there is never a fallback to a plaintext copy.
 *
 * [message] is a fixed, user-presentable text and never contains secret material.
 */
class BatchSecretUnavailableException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception(USER_MESSAGE, cause) {

    enum class Reason {
        /** Keystore key missing, invalidated, or the Keystore is unusable. */
        KEY_UNAVAILABLE,

        /** Ciphertext failed authentication or is malformed. */
        CORRUPTED,

        /** Envelope version or algorithm is not understood by this build. */
        UNSUPPORTED_FORMAT,

        /** Secrets were deliberately removed after the job reached a terminal state. */
        CLEARED,

        /** A required secret is not present in the stored configuration. */
        MISSING,

        /** The secret could not be protected when the job was created. */
        SEAL_FAILED
    }

    companion object {
        const val USER_MESSAGE =
            "The saved password is no longer available. Please create this job again and re-enter the password."
    }
}
