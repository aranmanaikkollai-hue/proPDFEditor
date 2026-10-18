package com.propdfeditor.batch.security

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.propdfeditor.batch.security.BatchSecretUnavailableException.Reason

/**
 * Keeps batch-job passwords out of the plaintext `batch_jobs.configJson` column.
 *
 * Stored form of a protected job (only the secret fields change; everything else stays readable):
 *
 * ```
 * {"allowPrinting":true,..., "sealedSecrets":{"v":1,"alg":"AES-256-GCM","fields":{"password":"<hex iv||ct||tag>"}}}
 * ```
 *
 * After the job reaches a terminal state the envelope is removed and `"secretsCleared":true` is
 * written, so a later attempt to run the job fails with [Reason.CLEARED] instead of running without
 * a password.
 *
 * Each field is encrypted separately with additional authenticated data
 * `propdf.batch.secret|v1|<jobId>|<fieldName>`, so a ciphertext copied to another job row or
 * another field does not authenticate.
 *
 * Pure Kotlin + Gson: unit-testable on the JVM with a fake [BatchSecretCipher].
 * No method here logs, and no exception carries secret material.
 */
object BatchJobSecrets {

    /** Configuration keys that hold secrets. */
    val SECRET_FIELDS: List<String> = listOf("password", "ownerPassword")

    const val SEALED_KEY = "sealedSecrets"
    const val CLEARED_KEY = "secretsCleared"
    const val FORMAT_VERSION = 1
    private const val ALGORITHM = "AES-256-GCM"

    private val gson = Gson()

    /** Result of [split]. [toString] never prints the secret values. */
    class Split(val publicJson: String, val secrets: Map<String, String>) {
        override fun toString(): String = "Split(secretFields=${secrets.keys})"
    }

    /** Separates the secret fields from the rest of the configuration. */
    fun split(configJson: String): Split {
        val obj = parse(configJson) ?: return Split(configJson, emptyMap())
        val secrets = LinkedHashMap<String, String>()
        for (field in SECRET_FIELDS) {
            val element = obj.get(field) ?: continue
            if (element.isJsonPrimitive) secrets[field] = element.asString
            obj.remove(field)
        }
        return Split(obj.toString(), secrets)
    }

    /** True if any secret field is stored in the clear (legacy rows). */
    fun hasPlaintextSecrets(configJson: String): Boolean {
        val obj = parse(configJson) ?: return false
        return SECRET_FIELDS.any { obj.get(it)?.isJsonPrimitive == true }
    }

    /** True if the configuration carries secret material the worker can still use. */
    fun hasSecretMaterial(configJson: String): Boolean {
        val obj = parse(configJson) ?: return false
        if (obj.has(CLEARED_KEY)) return false
        return obj.has(SEALED_KEY) || SECRET_FIELDS.any { obj.get(it)?.isJsonPrimitive == true }
    }

    /** True if the secrets were deliberately removed after a terminal state. */
    fun isCleared(configJson: String): Boolean = parse(configJson)?.has(CLEARED_KEY) == true

    /**
     * Encrypts [secrets] and returns [publicJson] plus the sealed envelope.
     * Throws [BatchSecretUnavailableException] (never returns plaintext) if encryption fails.
     */
    fun seal(
        publicJson: String,
        secrets: Map<String, String>,
        jobId: Long,
        cipher: BatchSecretCipher
    ): String {
        val obj = parse(publicJson) ?: throw BatchSecretUnavailableException(Reason.SEAL_FAILED)
        val fields = JsonObject()
        for ((name, value) in secrets) {
            if (name !in SECRET_FIELDS) throw BatchSecretUnavailableException(Reason.UNSUPPORTED_FORMAT)
            val plain = value.toByteArray(Charsets.UTF_8)
            try {
                fields.addProperty(name, toHex(cipher.encrypt(plain, aad(jobId, name))))
            } finally {
                plain.fill(0)
            }
        }
        val envelope = JsonObject()
        envelope.addProperty("v", FORMAT_VERSION)
        envelope.addProperty("alg", ALGORITHM)
        envelope.add("fields", fields)
        obj.remove(CLEARED_KEY)
        obj.add(SEALED_KEY, envelope)
        return obj.toString()
    }

    /**
     * Returns the configuration with the secret fields restored, for in-memory use by a worker.
     * The returned string must never be stored, logged or put into WorkManager data.
     *
     * Legacy rows that still hold plaintext secrets and have no envelope are returned as-is so
     * queued jobs created before this change keep working until they are migrated.
     *
     * @param required secret fields that must be present after resolution.
     */
    fun resolve(
        configJson: String,
        jobId: Long,
        cipher: BatchSecretCipher,
        required: Set<String> = emptySet()
    ): String {
        val obj = parse(configJson) ?: throw BatchSecretUnavailableException(Reason.CORRUPTED)
        if (obj.has(CLEARED_KEY)) throw BatchSecretUnavailableException(Reason.CLEARED)

        try {
            val sealed = obj.remove(SEALED_KEY)
            if (sealed != null) {
                if (!sealed.isJsonObject) throw BatchSecretUnavailableException(Reason.CORRUPTED)
                val envelope = sealed.asJsonObject
                val version = envelope.get("v")?.asInt
                val algorithm = envelope.get("alg")?.asString
                if (version != FORMAT_VERSION || algorithm != ALGORITHM) {
                    throw BatchSecretUnavailableException(Reason.UNSUPPORTED_FORMAT)
                }
                val fields = envelope.get("fields")
                if (fields == null || !fields.isJsonObject) {
                    throw BatchSecretUnavailableException(Reason.CORRUPTED)
                }
                for ((name, hex) in fields.asJsonObject.entrySet()) {
                    if (name !in SECRET_FIELDS) throw BatchSecretUnavailableException(Reason.UNSUPPORTED_FORMAT)
                    val plain = cipher.decrypt(fromHex(hex.asString), aad(jobId, name))
                    try {
                        obj.addProperty(name, String(plain, Charsets.UTF_8))
                    } finally {
                        plain.fill(0)
                    }
                }
            }
        } catch (e: BatchSecretUnavailableException) {
            throw e
        } catch (e: RuntimeException) {
            // Malformed envelope (bad hex, wrong JSON types, ...): treat as corrupted, never guess.
            throw BatchSecretUnavailableException(Reason.CORRUPTED, e)
        }

        for (name in required) {
            val element = obj.get(name)
            if (element == null || element.isJsonNull) throw BatchSecretUnavailableException(Reason.MISSING)
        }
        return obj.toString()
    }

    /**
     * Removes every secret (plaintext or sealed) and marks the configuration as cleared.
     * Configurations that never held secrets are returned unchanged.
     * Idempotent.
     */
    fun clear(configJson: String): String {
        val obj = parse(configJson) ?: return configJson
        val hadSecrets = obj.has(SEALED_KEY) || SECRET_FIELDS.any { obj.has(it) }
        if (!hadSecrets) return configJson
        SECRET_FIELDS.forEach { obj.remove(it) }
        obj.remove(SEALED_KEY)
        obj.addProperty(CLEARED_KEY, true)
        return obj.toString()
    }

    /**
     * Rewrites a legacy plaintext configuration into sealed form.
     * Returns null when there is nothing to migrate.
     */
    fun migrateLegacyPlaintext(configJson: String, jobId: Long, cipher: BatchSecretCipher): String? {
        if (!hasPlaintextSecrets(configJson)) return null
        val obj = parse(configJson) ?: return null
        if (obj.has(SEALED_KEY)) {
            // Sealed copy already exists: just drop the stray plaintext.
            SECRET_FIELDS.forEach { obj.remove(it) }
            return obj.toString()
        }
        val split = split(configJson)
        return seal(split.publicJson, split.secrets, jobId, cipher)
    }

    private fun aad(jobId: Long, field: String): ByteArray =
        "propdf.batch.secret|v$FORMAT_VERSION|$jobId|$field".toByteArray(Charsets.UTF_8)

    /** Parses to a JSON object, or null for blank/invalid input or any non-object JSON value. */
    private fun parse(json: String): JsonObject? = try {
        val element = gson.fromJson(json, JsonElement::class.java)
        if (element != null && element.isJsonObject) element.asJsonObject else null
    } catch (e: JsonParseException) {
        null
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
