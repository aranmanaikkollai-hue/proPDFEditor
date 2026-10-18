package com.propdfeditor.batch.security

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.propdfeditor.batch.data.database.BatchDatabase
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.security.BatchSecretUnavailableException.Reason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Batch password protection against the real Android Keystore and a real Room database file.
 *
 * Covers: no plaintext in the row or in the raw database / WAL bytes, decryption after the database
 * and cipher objects are re-created (simulated restart; a genuine process kill is NOT covered),
 * corrupted data, deleted Keystore key, cleanup after completed/failed/cancelled, failed sealing,
 * and migration of legacy plaintext rows.
 *
 * NOT covered here: WorkManager scheduling/retry, worker integration, log capture. See
 * BATCH_PASSWORD_STORAGE_SECURITY.md.
 */
@RunWith(AndroidJUnit4::class)
class BatchSecretPersistenceInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "secret_test_${System.nanoTime()}.db"
    private val alias = "propdf_test_alias_${System.nanoTime()}"
    private val userPw = "Pw-UNIQUE-4f9a1c-user"
    private val ownerPw = "Pw-UNIQUE-77be02-owner"
    private val input = listOf(Uri.parse("content://test/in/1.pdf"))

    private lateinit var db: BatchDatabase
    private lateinit var repo: BatchJobRepository

    private class FailingCipher : BatchSecretCipher {
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray =
            throw BatchSecretUnavailableException(Reason.KEY_UNAVAILABLE)

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray =
            throw BatchSecretUnavailableException(Reason.KEY_UNAVAILABLE)
    }

    @Before
    fun setUp() {
        open()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized && db.isOpen) db.close()
        context.deleteDatabase(dbName)
        deleteKey()
    }

    private fun open(cipher: BatchSecretCipher = AndroidKeystoreBatchSecretCipher(alias)) {
        db = Room.databaseBuilder(context, BatchDatabase::class.java, dbName).build()
        repo = BatchJobRepository(context, db, BatchSecretStore(cipher))
    }

    private fun reopen(cipher: BatchSecretCipher = AndroidKeystoreBatchSecretCipher(alias)) {
        db.close()
        open(cipher)
    }

    private fun deleteKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun encryptConfig(): String {
        val o = JsonObject()
        o.addProperty("password", userPw)
        o.addProperty("ownerPassword", ownerPw)
        o.addProperty("allowPrinting", true)
        o.addProperty("encryptionLevel", "AES_256")
        o.addProperty("outputDirUri", "content://tree/out")
        return Gson().toJson(o)
    }

    private fun rawDatabaseBytes(): ByteArray {
        val main = context.getDatabasePath(dbName)
        val parts = listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal"))
        return parts.filter { it.exists() }.map { it.readBytes() }.fold(ByteArray(0)) { a, b -> a + b }
    }

    private fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.UTF_8)
        if (n.isEmpty() || haystack.size < n.size) return false
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    private fun rowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM batch_jobs").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun expectReason(expected: Reason, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected BatchSecretUnavailableException($expected)")
        } catch (e: BatchSecretUnavailableException) {
            assertEquals(expected, e.reason)
        }
    }

    // 1. plaintext absence

    @Test
    fun createJob_persistsNoPlaintext_inRowOrDatabaseFiles() = runBlocking {
        val id = repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig())
        val row = db.batchJobDao().getById(id)!!
        assertFalse(row.configJson.contains(userPw))
        assertFalse(row.configJson.contains(ownerPw))
        assertTrue(row.configJson.contains("sealedSecrets"))

        assertFalse(contains(rawDatabaseBytes(), userPw))
        assertFalse(contains(rawDatabaseBytes(), ownerPw))
        reopen() // closing checkpoints the write-ahead log into the main file
        assertFalse(contains(rawDatabaseBytes(), userPw))
        assertFalse(contains(rawDatabaseBytes(), ownerPw))
    }

    // 2./3. retrieval, also after re-creating the database and cipher objects

    @Test
    fun secrets_decryptCorrectly_andSurviveRecreationOfDatabaseAndCipher() = runBlocking {
        val id = repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig())
        reopen()
        val resolved = Gson().fromJson(
            repo.resolveJobConfig(db.batchJobDao().getById(id)!!, setOf("password")), JsonObject::class.java
        )
        assertEquals(userPw, resolved.get("password").asString)
        assertEquals(ownerPw, resolved.get("ownerPassword").asString)
        assertEquals("content://tree/out", resolved.get("outputDirUri").asString)
    }

    // 4. corrupted data

    @Test
    fun corruptedEnvelope_failsSafely() {
        val id = runBlocking { repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig()) }
        val stored = runBlocking { db.batchJobDao().getById(id)!! }
        val o = Gson().fromJson(stored.configJson, JsonObject::class.java)
        val fields = o.getAsJsonObject("sealedSecrets").getAsJsonObject("fields")
        val hex = fields.get("password").asString
        fields.addProperty("password", hex.substring(0, hex.length - 1) + if (hex.last() == '0') '1' else '0')
        runBlocking { db.batchJobDao().updateConfigJson(id, o.toString()) }
        expectReason(Reason.CORRUPTED) {
            repo.resolveJobConfig(db.batchJobDao().getById(id)!!, setOf("password"))
        }
    }

    // 5. Keystore key unavailable / invalidated

    @Test
    fun deletedKeystoreKey_makesJobUnrecoverable_withoutFallback() {
        val id = runBlocking { repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig()) }
        deleteKey()
        expectReason(Reason.KEY_UNAVAILABLE) {
            repo.resolveJobConfig(db.batchJobDao().getById(id)!!, setOf("password"))
        }
    }

    @Test
    fun createJob_whenSealingFails_leavesNoRowAndNoPlaintext() {
        reopen(FailingCipher())
        expectReason(Reason.KEY_UNAVAILABLE) {
            repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig())
        }
        assertEquals(0, rowCount())
        assertFalse(contains(rawDatabaseBytes(), userPw))
        assertFalse(contains(rawDatabaseBytes(), ownerPw))
    }

    // 6./7./8./9. terminal states clear secrets

    @Test
    fun terminalStates_clearSecrets_andJobCannotRunAgain() = runBlocking {
        for (status in listOf(BatchJobStatus.COMPLETED, BatchJobStatus.FAILED, BatchJobStatus.CANCELLED)) {
            val id = repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig())
            repo.updateStatus(id, status)
            repo.clearSecrets(id)
            val row = db.batchJobDao().getById(id)!!
            assertFalse(status.name, row.configJson.contains("sealedSecrets"))
            assertFalse(status.name, row.configJson.contains(userPw))
            assertTrue(status.name, row.configJson.contains("secretsCleared"))
            expectReasonSuspend(Reason.CLEARED) { repo.resolveJobConfig(row, setOf("password")) }
        }
    }

    @Test
    fun cancelThenClear_removesSecrets() = runBlocking {
        val id = repo.createJob(BatchJobType.DECRYPT, input, """{"password":"$userPw","outputDirUri":"content://tree/out"}""")
        repo.cancelJob(id)
        repo.clearSecrets(id)
        val row = db.batchJobDao().getById(id)!!
        assertEquals(BatchJobStatus.CANCELLED, row.status)
        assertFalse(row.configJson.contains("sealedSecrets"))
        assertFalse(row.configJson.contains(userPw))
    }

    @Test
    fun deleteJob_removesRow() = runBlocking {
        val id = repo.createJob(BatchJobType.ENCRYPT, input, encryptConfig())
        repo.deleteJob(db.batchJobDao().getById(id)!!)
        assertEquals(0, rowCount())
    }

    @Test
    fun jobsWithoutSecrets_areStoredUnchanged() = runBlocking {
        val id = repo.createJob(BatchJobType.COMPRESS, input, """{"quality":80}""")
        val row = db.batchJobDao().getById(id)!!
        assertEquals(80, Gson().fromJson(row.configJson, JsonObject::class.java).get("quality").asInt)
        assertFalse(row.configJson.contains("sealedSecrets"))
        assertEquals(row.configJson, repo.resolveJobConfig(row))
    }

    // 12. backward compatibility with rows written by older versions

    @Test
    fun legacyPlaintextRows_areMigratedOrScrubbed() = runBlocking {
        val dao = db.batchJobDao()
        val legacy = encryptConfig()
        val pending = dao.insert(BatchJobEntity(type = BatchJobType.ENCRYPT, status = BatchJobStatus.PENDING, inputUris = input, configJson = legacy))
        val done = dao.insert(BatchJobEntity(type = BatchJobType.ENCRYPT, status = BatchJobStatus.COMPLETED, inputUris = input, configJson = legacy))
        val failed = dao.insert(BatchJobEntity(type = BatchJobType.DECRYPT, status = BatchJobStatus.FAILED, inputUris = input, configJson = legacy))

        val result = repo.migrateLegacyPlaintextSecrets()
        assertEquals(BatchJobRepository.SecretMigrationResult(sealed = 1, scrubbed = 2, failed = 0), result)

        for (id in listOf(pending, done, failed)) {
            val cfg = dao.getById(id)!!.configJson
            assertFalse(cfg.contains(userPw))
            assertFalse(cfg.contains(ownerPw))
        }
        val resolved = Gson().fromJson(
            repo.resolveJobConfig(dao.getById(pending)!!, setOf("password")), JsonObject::class.java
        )
        assertEquals(userPw, resolved.get("password").asString)
        assertEquals(BatchJobStatus.PENDING, dao.getById(pending)!!.status)
        // running it again changes nothing
        assertEquals(BatchJobRepository.SecretMigrationResult(0, 0, 0), repo.migrateLegacyPlaintextSecrets())
    }

    @Test
    fun legacyPendingRow_whenKeystoreUnusable_isFailedAndScrubbed_neverLeftInTheClear() = runBlocking {
        val dao = db.batchJobDao()
        val id = dao.insert(BatchJobEntity(type = BatchJobType.ENCRYPT, status = BatchJobStatus.PENDING, inputUris = input, configJson = encryptConfig()))
        reopen(FailingCipher())
        val result = repo.migrateLegacyPlaintextSecrets()
        assertEquals(1, result.failed)
        val row = db.batchJobDao().getById(id)!!
        assertEquals(BatchJobStatus.FAILED, row.status)
        assertFalse(row.configJson.contains(userPw))
        assertFalse(row.configJson.contains(ownerPw))
    }

    private suspend fun expectReasonSuspend(expected: Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected BatchSecretUnavailableException($expected)")
        } catch (e: BatchSecretUnavailableException) {
            assertEquals(expected, e.reason)
        }
    }
}
