package com.propdf.core.domain.repository

import com.propdf.core.domain.model.RecentFile

/**
 * Explicit outcome of a read-only "does this document already exist?" lookup.
 *
 * The three states are deliberately distinct so that a failed lookup can never be mistaken for a
 * genuinely missing record (which would let a caller insert over user-managed data):
 *  - [Found]: the database answered and the record exists.
 *  - [NotFound]: the database answered successfully and the record genuinely does not exist.
 *  - [Failed]: the lookup could not complete (database/I-O/unexpected error).
 */
sealed interface RecentFileLookup {
    data class Found(val file: RecentFile) : RecentFileLookup
    object NotFound : RecentFileLookup

    /**
     * @property cause the underlying error; never swallowed. Do not log its message verbatim
     * because it may contain a document URI.
     * @property retryable true only for errors that are plausibly transient (locked/busy database,
     * transient I/O). Permanent errors must not be retried indefinitely.
     */
    data class Failed(val cause: Throwable, val retryable: Boolean) : RecentFileLookup
}

/** Outcome of an insert that must never overwrite an existing row. */
sealed interface RecentFileInsertResult {
    /** A new row was written. */
    object Inserted : RecentFileInsertResult

    /** A row with this URI already existed; nothing was written or modified. */
    object AlreadyExists : RecentFileInsertResult

    data class Failed(val cause: Throwable, val retryable: Boolean) : RecentFileInsertResult
}
