package com.propdfeditor.batch.security

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.propdfeditor.batch.security.BatchSecretUnavailableException.Reason
import com.propdfeditor.batch.worker.DecryptWorker
import com.propdfeditor.batch.worker.EncryptWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JVM tests for [BatchJobSecrets]: envelope format, authenticated binding to the job id, failure
 * handling, cleanup, and legacy-row migration. They use a software AES-GCM cipher; the Android
 * Keystore implementation is covered by the instrumented test (NOT RUN in the authoring environment).
 */
class BatchJobSecretsTest {

    private val userPw = "S3cret \"quoted\" \\ p\u00e4ss-\u0baa\u0bbe\u0bb8\u0bcd"
    private val ownerPw = "0wner-different-pw"
    private val jobId = 41L

    /** AES-256-GCM with a throw-away software key (stands in for the Keystore key). */
    private class SoftwareGcmCipher(
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    ) : BatchSecretCipher {
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return iv + c.doFinal(plaintext)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            if (blob.size < 12 + 16) throw BatchSecretUnavailableException(Reason.CORRUPTED)
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                c.updateAAD(aad)
                return c.doFinal(blob.copyOfRange(12, blob.size))
            } catch (e: AEADBadTagException) {
                throw BatchSecretUnavailableException(Reason.CORRUPTED, e)
            }
        }
    }

    /** Simulates a missing/invalidated Keystore key. */
    private class UnavailableCipher : BatchSecretCipher {
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
            throw BatchSecretUnavailableException(Reason.KEY_UNAVAILABLE)

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray =
            throw BatchSecretUnavailableException(Reason.KEY_UNAVAILABLE)
    }

    private val cipher = SoftwareGcmCipher()

    private fun config(password: String = userPw, owner: String? = ownerPw): String {
        val o = JsonObject()
        o.addProperty("password", password)
        if (owner != null) o.addProperty("ownerPassword", owner)
        o.addProperty("allowPrinting", true)
        o.addProperty("allowCopying", false)
        o.addProperty("allowModifying", false)
        o.addProperty("encryptionLevel", "AES_256")
        o.addProperty("outputDirUri", "content://tree/out")
        return Gson().toJson(o)
    }

    private fun sealed(json: String = config(), id: Long = jobId, c: BatchSecretCipher = cipher): String {
        val split = BatchJobSecrets.split(json)
        return BatchJobSecrets.seal(split.publicJson, split.secrets, id, c)
    }

    private fun expectReason(expected: Reason, block: () -> Unit) {
        try {
            block()
            fail("expected BatchSecretUnavailableException($expected)")
        } catch (e: BatchSecretUnavailableException) {
            assertEquals(expected, e.reason)
        }
    }

    private fun obj(json: String): JsonObject = Gson().fromJson(json, JsonObject::class.java)

    // ---- 1. plaintext absence in what gets persisted ----------------------------------------

    @Test
    fun split_removesSecretsAndKeepsEverythingElse() {
        val split = BatchJobSecrets.split(config())
        assertEquals(userPw, split.secrets["password"])
        assertEquals(ownerPw, split.secrets["ownerPassword"])
        val pub = obj(split.publicJson)
        assertFalse(pub.has("password"))
        assertFalse(pub.has("ownerPassword"))
        assertEquals("AES_256", pub.get("encryptionLevel").asString)
        assertEquals("content://tree/out", pub.get("outputDirUri").asString)
        assertFalse(split.publicJson.contains(ownerPw))
    }

    @Test
    fun sealedConfig_containsNoPlaintextAndHasEnvelope() {
        val stored = sealed()
        assertFalse(stored.contains(ownerPw))
        assertFalse(stored.contains("0wner"))
        assertFalse(stored.contains("S3cret"))
        assertFalse(Gson().toJson(userPw).trim('"').let { stored.contains(it) })
        val o = obj(stored)
        assertFalse(o.has("password"))
        assertFalse(o.has("ownerPassword"))
        assertTrue(o.has(BatchJobSecrets.SEALED_KEY))
        assertEquals(1, o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).get("v").asInt)
        assertFalse(BatchJobSecrets.hasPlaintextSecrets(stored))
        assertTrue(BatchJobSecrets.hasSecretMaterial(stored))
    }

    @Test
    fun everySeal_usesAFreshIv() {
        assertNotEquals(sealed(), sealed())
    }

    // ---- 2./11. retrieval, user vs owner password ------------------------------------------

    @Test
    fun resolve_restoresBothDistinctPasswords() {
        val resolved = obj(BatchJobSecrets.resolve(sealed(), jobId, cipher, setOf("password")))
        assertEquals(userPw, resolved.get("password").asString)
        assertEquals(ownerPw, resolved.get("ownerPassword").asString)
        assertFalse(resolved.has(BatchJobSecrets.SEALED_KEY))
        assertEquals("AES_256", resolved.get("encryptionLevel").asString)
    }

    @Test
    fun resolve_singlePasswordMode_hasNoOwnerPassword() {
        val stored = sealed(config(owner = null))
        val resolved = obj(BatchJobSecrets.resolve(stored, jobId, cipher, setOf("password")))
        assertEquals(userPw, resolved.get("password").asString)
        assertFalse(resolved.has("ownerPassword"))
    }

    @Test
    fun resolvedConfig_deserialisesIntoWorkerConfig() {
        val resolved = BatchJobSecrets.resolve(sealed(), jobId, cipher, setOf("password"))
        val cfg = Gson().fromJson(resolved, EncryptWorker.EncryptConfig::class.java)
        assertEquals(userPw, cfg.password)
        assertEquals(ownerPw, cfg.ownerPassword)
        assertEquals("content://tree/out", cfg.outputDirUri)
    }

    // ---- 4. corruption / unsupported / wrong binding ----------------------------------------

    @Test
    fun tamperedCiphertext_failsAndNeverFallsBackToPlaintext() {
        val o = obj(sealed())
        val fields = o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).getAsJsonObject("fields")
        val hex = fields.get("password").asString
        val flipped = hex.substring(0, hex.length - 1) + if (hex.last() == '0') '1' else '0'
        fields.addProperty("password", flipped)
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(o.toString(), jobId, cipher, setOf("password")) }
    }

    @Test
    fun malformedHex_isCorrupted() {
        val o = obj(sealed())
        o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).getAsJsonObject("fields").addProperty("password", "zz-not-hex")
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(o.toString(), jobId, cipher) }
    }

    @Test
    fun envelopeWithWrongShape_isCorrupted() {
        val o = obj(sealed())
        o.addProperty(BatchJobSecrets.SEALED_KEY, "not an object")
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(o.toString(), jobId, cipher) }
    }

    @Test
    fun unsupportedVersion_isRejected() {
        val o = obj(sealed())
        o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).addProperty("v", 2)
        expectReason(Reason.UNSUPPORTED_FORMAT) { BatchJobSecrets.resolve(o.toString(), jobId, cipher) }
    }

    @Test
    fun unknownSecretFieldName_isRejected() {
        val o = obj(sealed())
        val fields = o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).getAsJsonObject("fields")
        fields.addProperty("outputDirUri", fields.get("password").asString)
        expectReason(Reason.UNSUPPORTED_FORMAT) { BatchJobSecrets.resolve(o.toString(), jobId, cipher) }
    }

    @Test
    fun ciphertextMovedToAnotherJob_doesNotAuthenticate() {
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(sealed(id = 41), 42, cipher) }
    }

    @Test
    fun ciphertextMovedToAnotherField_doesNotAuthenticate() {
        val o = obj(sealed())
        val fields = o.getAsJsonObject(BatchJobSecrets.SEALED_KEY).getAsJsonObject("fields")
        val owner = fields.get("ownerPassword").asString
        fields.addProperty("password", owner)
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(o.toString(), jobId, cipher) }
    }

    // ---- 5. key unavailable / invalidated ---------------------------------------------------

    @Test
    fun keyUnavailable_onResolve_failsSafely() {
        val stored = sealed()
        expectReason(Reason.KEY_UNAVAILABLE) { BatchJobSecrets.resolve(stored, jobId, UnavailableCipher()) }
    }

    @Test
    fun differentKey_isTreatedAsCorrupted_notAsPlaintext() {
        // e.g. database restored to a device whose Keystore holds a different key
        val stored = sealed(c = SoftwareGcmCipher())
        expectReason(Reason.CORRUPTED) { BatchJobSecrets.resolve(stored, jobId, SoftwareGcmCipher()) }
    }

    @Test
    fun keyUnavailable_onSeal_failsAndProducesNothing() {
        val split = BatchJobSecrets.split(config())
        expectReason(Reason.KEY_UNAVAILABLE) {
            BatchJobSecrets.seal(split.publicJson, split.secrets, jobId, UnavailableCipher())
        }
    }

    @Test
    fun sealingAnUnparseableConfiguration_fails() {
        expectReason(Reason.SEAL_FAILED) { BatchJobSecrets.seal("not json", mapOf("password" to "x"), jobId, cipher) }
    }

    // ---- 7./9. cleanup after completion / cancellation / failure ----------------------------

    @Test
    fun clear_removesSealedSecretsAndMarksConfiguration() {
        val cleared = BatchJobSecrets.clear(sealed())
        val o = obj(cleared)
        assertFalse(o.has(BatchJobSecrets.SEALED_KEY))
        assertFalse(o.has("password"))
        assertTrue(o.get(BatchJobSecrets.CLEARED_KEY).asBoolean)
        assertEquals("AES_256", o.get("encryptionLevel").asString)
        assertFalse(BatchJobSecrets.hasSecretMaterial(cleared))
        assertTrue(BatchJobSecrets.isCleared(cleared))
        assertFalse(cleared.contains("sealedSecrets"))
    }

    @Test
    fun clear_removesLegacyPlaintext() {
        val cleared = BatchJobSecrets.clear(config())
        assertFalse(cleared.contains(userPw.take(6)))
        assertFalse(cleared.contains(ownerPw))
        assertFalse(BatchJobSecrets.hasPlaintextSecrets(cleared))
    }

    @Test
    fun clear_isIdempotent() {
        val once = BatchJobSecrets.clear(sealed())
        assertEquals(once, BatchJobSecrets.clear(once))
    }

    @Test
    fun clear_leavesConfigurationsWithoutSecretsUntouched() {
        val plain = """{"quality":80}"""
        assertEquals(plain, BatchJobSecrets.clear(plain))
        assertFalse(BatchJobSecrets.isCleared(plain))
    }

    @Test
    fun resolveAfterClear_failsInsteadOfRunningWithoutPassword() {
        expectReason(Reason.CLEARED) { BatchJobSecrets.resolve(BatchJobSecrets.clear(sealed()), jobId, cipher) }
    }

    @Test
    fun missingRequiredSecret_isReported() {
        expectReason(Reason.MISSING) { BatchJobSecrets.resolve("{}", jobId, cipher, setOf("password")) }
    }

    // ---- 12. backward compatibility with rows queued before this change ----------------------

    @Test
    fun legacyPlaintextRow_isStillUsableUntilMigrated() {
        val legacy = config()
        assertTrue(BatchJobSecrets.hasPlaintextSecrets(legacy))
        val resolved = obj(BatchJobSecrets.resolve(legacy, jobId, cipher, setOf("password")))
        assertEquals(userPw, resolved.get("password").asString)
    }

    @Test
    fun migrateLegacyPlaintext_sealsInPlaceAndStaysRunnable() {
        val migrated = BatchJobSecrets.migrateLegacyPlaintext(config(), jobId, cipher)!!
        assertFalse(BatchJobSecrets.hasPlaintextSecrets(migrated))
        assertFalse(migrated.contains(ownerPw))
        val resolved = obj(BatchJobSecrets.resolve(migrated, jobId, cipher, setOf("password")))
        assertEquals(userPw, resolved.get("password").asString)
        assertEquals(ownerPw, resolved.get("ownerPassword").asString)
    }

    @Test
    fun migrateLegacyPlaintext_nothingToDo_returnsNull() {
        assertNull(BatchJobSecrets.migrateLegacyPlaintext("""{"quality":80}""", jobId, cipher))
        assertNull(BatchJobSecrets.migrateLegacyPlaintext(sealed(), jobId, cipher))
    }

    @Test
    fun migrateLegacyPlaintext_whenKeyUnavailable_throwsAndNeverReturnsPlaintext() {
        expectReason(Reason.KEY_UNAVAILABLE) {
            BatchJobSecrets.migrateLegacyPlaintext(config(), jobId, UnavailableCipher())
        }
    }

    @Test
    fun migrateLegacyPlaintext_withStrayPlaintextNextToEnvelope_dropsPlaintextKeepsEnvelope() {
        val o = obj(sealed())
        o.addProperty("password", userPw)
        val migrated = BatchJobSecrets.migrateLegacyPlaintext(o.toString(), jobId, cipher)!!
        assertFalse(BatchJobSecrets.hasPlaintextSecrets(migrated))
        val resolved = obj(BatchJobSecrets.resolve(migrated, jobId, cipher, setOf("password")))
        assertEquals(userPw, resolved.get("password").asString)
    }

    @Test
    fun configurationsWithoutSecrets_areNotModifiedBySplit() {
        val plain = """{"quality":80,"mode":"x"}"""
        val split = BatchJobSecrets.split(plain)
        assertTrue(split.secrets.isEmpty())
        assertEquals(obj(plain), obj(split.publicJson))
    }

    @Test
    fun unparseableConfiguration_isReturnedUnchangedBySplitAndClear() {
        assertEquals("not json", BatchJobSecrets.split("not json").publicJson)
        assertEquals("not json", BatchJobSecrets.clear("not json"))
    }

    // ---- 10. nothing secret in printable representations -------------------------------------

    @Test
    fun printableRepresentations_neverContainSecrets() {
        val enc = EncryptWorker.EncryptConfig(
            password = userPw, ownerPassword = ownerPw, outputDirUri = "content://tree/out"
        )
        val dec = DecryptWorker.DecryptConfig(password = userPw, outputDirUri = "content://tree/out")
        val split = BatchJobSecrets.split(config())
        val texts = listOf(
            enc.toString(), dec.toString(), split.toString(),
            BatchSecretUnavailableException(Reason.CORRUPTED).message.orEmpty(),
            BatchSecretUnavailableException.USER_MESSAGE
        )
        for (t in texts) {
            assertFalse(t, t.contains(ownerPw))
            assertFalse(t, t.contains("S3cret"))
        }
    }
}
