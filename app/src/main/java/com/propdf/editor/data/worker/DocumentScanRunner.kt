package com.propdf.editor.data.worker

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileInsertResult
import com.propdf.core.domain.repository.RecentFileLookup
import com.propdf.editor.domain.repository.DocumentRepository
import kotlinx.coroutines.CancellationException

/** Source of discovered PDFs (MediaStore in production, a controlled list in tests). */
interface PdfDiscoverySource {
    suspend fun forEachPdf(action: suspend (RecentFile) -> Unit)
}

/** What the scan did, without any Android dependency. Contains no URIs, names or paths. */
data class ScanOutcome(
    val discovered: Int = 0,
    val inserted: Int = 0,
    val alreadyKnown: Int = 0,
    val lookupFailures: Int = 0,
    val insertFailures: Int = 0,
    /** True if at least one failure was permanent (or of unknown kind). */
    val hadPermanentFailure: Boolean = false,
    /** True if at least one failure was plausibly transient. */
    val hadTransientFailure: Boolean = false,
    /** First underlying error, preserved for diagnostics (log the class only, never the message). */
    val firstFailure: Throwable? = null,
    /** The discovery source itself failed before or during enumeration. */
    val sourceFailed: Boolean = false
) {
    val hadFailure: Boolean get() = lookupFailures > 0 || insertFailures > 0 || sourceFailed
}

enum class ScanDecision { SUCCESS, RETRY, FAILURE }

/**
 * Production decision logic of the document scan. [DocumentScanWorker] only wires Android pieces to
 * this class, so tests exercise exactly the code that runs in production.
 *
 * Rules:
 *  - insert only when the lookup is explicitly NOT_FOUND;
 *  - FOUND -> never insert or modify;
 *  - LOOKUP_FAILED, a thrown error, or an invalid result (a FOUND row for a different URI) -> never insert.
 */
class DocumentScanRunner(
    private val source: PdfDiscoverySource,
    private val repository: DocumentRepository
) {
    suspend fun run(): ScanOutcome {
        var o = ScanOutcome()
        fun fail(cause: Throwable, retryable: Boolean, lookup: Boolean) {
            o = o.copy(
                lookupFailures = o.lookupFailures + if (lookup) 1 else 0,
                insertFailures = o.insertFailures + if (lookup) 0 else 1,
                hadTransientFailure = o.hadTransientFailure || retryable,
                hadPermanentFailure = o.hadPermanentFailure || !retryable,
                firstFailure = o.firstFailure ?: cause
            )
        }
        try {
            source.forEachPdf { pdf ->
                o = o.copy(discovered = o.discovered + 1)
                val lookup: RecentFileLookup = try {
                    repository.lookupRecentFile(pdf.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    RecentFileLookup.Failed(e, retryable = false) // unexpected: not proof of absence
                }
                when (lookup) {
                    is RecentFileLookup.Found ->
                        if (lookup.file.uri == pdf.uri) {
                            o = o.copy(alreadyKnown = o.alreadyKnown + 1)
                        } else {
                            fail(IllegalStateException("Lookup returned a different record"), false, true)
                        }
                    is RecentFileLookup.Failed -> fail(lookup.cause, lookup.retryable, true)
                    RecentFileLookup.NotFound -> {
                        val result = try {
                            repository.insertRecentFileIfAbsent(pdf)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            RecentFileInsertResult.Failed(e, retryable = false)
                        }
                        when (result) {
                            RecentFileInsertResult.Inserted -> o = o.copy(inserted = o.inserted + 1)
                            RecentFileInsertResult.AlreadyExists -> o = o.copy(alreadyKnown = o.alreadyKnown + 1)
                            is RecentFileInsertResult.Failed -> fail(result.cause, result.retryable, false)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            o = o.copy(
                sourceFailed = true,
                hadTransientFailure = o.hadTransientFailure || e is java.io.IOException,
                hadPermanentFailure = o.hadPermanentFailure || e !is java.io.IOException,
                firstFailure = o.firstFailure ?: e
            )
        }
        return o
    }

    companion object {
        /** Give transient failures a few chances, then stop (no infinite retry). */
        const val MAX_ATTEMPTS = 3

        /**
         * Success only when nothing failed. Retry only if every failure was transient and attempts remain.
         * [runAttemptCount] is WorkManager's zero-based attempt counter.
         */
        fun decide(outcome: ScanOutcome, runAttemptCount: Int): ScanDecision = when {
            !outcome.hadFailure -> ScanDecision.SUCCESS
            outcome.hadPermanentFailure -> ScanDecision.FAILURE
            runAttemptCount + 1 < MAX_ATTEMPTS -> ScanDecision.RETRY
            else -> ScanDecision.FAILURE
        }
    }
}
