package com.propdfeditor.batch.repository

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import com.propdfeditor.batch.data.database.BatchDatabase
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.security.BatchJobSecrets
import com.propdfeditor.batch.security.BatchSecretStore
import com.propdfeditor.batch.security.BatchSecretUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchJobRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BatchDatabase,
    private val secretStore: BatchSecretStore
) {
    private val dao = database.batchJobDao()

    val allJobs: Flow<List<BatchJobEntity>> = dao.getAll()
    val activeJobs: Flow<List<BatchJobEntity>> = dao.getActiveJobs()

    /**
     * Creates a job. Secret fields in [configJson] (see [BatchJobSecrets.SECRET_FIELDS]) are never
     * written in the clear: the row is inserted with the secret-free configuration, then the
     * secrets are encrypted with the Keystore-backed cipher bound to the new job id and stored as an
     * envelope. If protection fails the row is removed again and
     * [BatchSecretUnavailableException] is thrown; there is no plaintext fallback.
     */
    suspend fun createJob(
        type: BatchJobType,
        inputUris: List<Uri>,
        configJson: String = "{}",
        outputUri: Uri? = null
    ): Long = withContext(Dispatchers.IO) {
        val split = secretStore.split(configJson)
        val job = BatchJobEntity(
            type = type,
            inputUris = inputUris,
            outputUri = outputUri,
            configJson = split.publicJson,
            totalItems = inputUris.size
        )
        val id = dao.insert(job)
        if (split.secrets.isNotEmpty()) {
            try {
                dao.updateConfigJson(id, secretStore.seal(split.publicJson, split.secrets, id))
            } catch (e: Exception) {
                withContext(NonCancellable) { dao.deleteById(id) }
                if (e is CancellationException) throw e
                if (e is BatchSecretUnavailableException) throw e
                throw BatchSecretUnavailableException(BatchSecretUnavailableException.Reason.SEAL_FAILED, e)
            }
        }
        id
    }

    /**
     * The job's configuration with secrets restored, for in-memory use by a worker only.
     * Throws [BatchSecretUnavailableException] if a required secret cannot be recovered.
     */
    suspend fun resolveJobConfig(job: BatchJobEntity, requiredSecrets: Set<String> = emptySet()): String =
        withContext(Dispatchers.IO) { secretStore.resolve(job.configJson, job.id, requiredSecrets) }

    /**
     * Removes stored secrets (sealed or legacy plaintext) for a job. Call on every terminal state.
     * Deleting the envelope is not physical erasure: SQLite free pages, the write-ahead log and
     * earlier backups may still contain older copies. See BATCH_PASSWORD_STORAGE_SECURITY.md.
     */
    suspend fun clearSecrets(jobId: Long): Unit = withContext(Dispatchers.IO) {
        val job = dao.getById(jobId)
        if (job != null) {
            val cleared = BatchJobSecrets.clear(job.configJson)
            if (cleared != job.configJson) {
                dao.updateConfigJson(jobId, cleared)
            }
        }
    }

    suspend fun updateWorkRequestId(jobId: Long, workRequestId: String?): Unit = withContext(Dispatchers.IO) {
        dao.updateWorkRequestId(jobId, workRequestId)
    }

    /** Result of [migrateLegacyPlaintextSecrets]; counts only, never content. */
    data class SecretMigrationResult(val sealed: Int, val scrubbed: Int, val failed: Int)

    /**
     * One-time handling of rows written before secrets were protected:
     *  - terminal rows (completed, failed, cancelled): secrets are scrubbed;
     *  - queued/running/paused rows: secrets are encrypted in place so the job still runs;
     *  - if a row cannot be protected (Keystore unusable) it is marked FAILED and scrubbed. A queued
     *    job is lost rather than leaving its password in the clear; the user re-creates it.
     * Safe to run repeatedly.
     */
    suspend fun migrateLegacyPlaintextSecrets(): SecretMigrationResult = withContext(Dispatchers.IO) {
        var sealed = 0
        var scrubbed = 0
        var failed = 0
        for (job in dao.getRowsMentioningPassword()) {
            if (!BatchJobSecrets.hasPlaintextSecrets(job.configJson)) continue
            val terminal = job.status == BatchJobStatus.COMPLETED ||
                job.status == BatchJobStatus.FAILED ||
                job.status == BatchJobStatus.CANCELLED
            if (terminal) {
                dao.updateConfigJson(job.id, BatchJobSecrets.clear(job.configJson))
                scrubbed++
                continue
            }
            try {
                val migrated = secretStore.migrateLegacyPlaintext(job.configJson, job.id)
                if (migrated != null) {
                    // Compare-and-set: a worker may have finished and cleared this row since it was
                    // read; never overwrite that with a freshly sealed copy. If the row changed it
                    // is simply left for the next startup pass.
                    val changed = dao.updateConfigJsonIfUnchanged(job.id, job.configJson, migrated)
                    if (changed > 0) sealed++
                }
            } catch (e: BatchSecretUnavailableException) {
                Timber.w("Legacy batch job %d could not be protected (%s); scrubbing", job.id, e.reason)
                dao.updateStatus(job.id, BatchJobStatus.FAILED, BatchSecretUnavailableException.USER_MESSAGE)
                dao.updateConfigJson(job.id, BatchJobSecrets.clear(job.configJson))
                failed++
            }
        }
        SecretMigrationResult(sealed, scrubbed, failed)
    }

    suspend fun updateJob(job: BatchJobEntity) = withContext(Dispatchers.IO) {
        dao.update(job)
    }

    suspend fun updateProgress(jobId: Long, progress: Int, processed: Int) = withContext(Dispatchers.IO) {
        dao.updateProgress(jobId, progress, processed)
    }

    suspend fun updateStatus(jobId: Long, status: BatchJobStatus, error: String? = null) = withContext(Dispatchers.IO) {
        dao.updateStatus(jobId, status, error)
    }

    suspend fun getJobById(id: Long): BatchJobEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun cancelJob(id: Long) = withContext(Dispatchers.IO) {
        dao.cancelJob(id)
    }

    suspend fun deleteJob(job: BatchJobEntity) = withContext(Dispatchers.IO) {
        dao.delete(job)
    }

    suspend fun deleteOldCompletedJobs(olderThanMillis: Long = 7 * 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dao.deleteOldCompletedJobs(cutoff)
    }

    suspend fun getRunningJobCount(): Int = withContext(Dispatchers.IO) {
        dao.getRunningJobCount()
    }
}
