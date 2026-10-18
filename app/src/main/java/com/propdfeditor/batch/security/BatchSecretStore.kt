package com.propdfeditor.batch.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, injectable wrapper binding [BatchJobSecrets] to a [BatchSecretCipher].
 * Production uses the Android Keystore cipher; tests pass a fake through the secondary constructor.
 */
@Singleton
class BatchSecretStore(private val cipher: BatchSecretCipher) {

    @Inject
    constructor() : this(AndroidKeystoreBatchSecretCipher())

    fun split(configJson: String): BatchJobSecrets.Split = BatchJobSecrets.split(configJson)

    fun seal(publicJson: String, secrets: Map<String, String>, jobId: Long): String =
        BatchJobSecrets.seal(publicJson, secrets, jobId, cipher)

    fun resolve(configJson: String, jobId: Long, required: Set<String> = emptySet()): String =
        BatchJobSecrets.resolve(configJson, jobId, cipher, required)

    fun migrateLegacyPlaintext(configJson: String, jobId: Long): String? =
        BatchJobSecrets.migrateLegacyPlaintext(configJson, jobId, cipher)
}
