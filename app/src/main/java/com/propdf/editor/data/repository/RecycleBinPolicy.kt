package com.propdf.editor.data.repository

import java.util.concurrent.TimeUnit

/**
 * Pure recycle-bin age rules (no Android, no database), so they can be unit-tested.
 *
 * History: the previous inline expression `days * 24 * 60 * 60 * 1000` was evaluated in Int and
 * overflows for days >= 25 (30 days gives -1,702,967,296), which moved the cutoff into the FUTURE and made
 * every bin item "old enough" to be purged. All arithmetic here is Long.
 */
object RecycleBinPolicy {
    const val MIN_AUTO_DELETE_DAYS = 1
    const val MAX_AUTO_DELETE_DAYS = 90

    /** Items deleted strictly before the returned instant are eligible. [days] must be >= 0. */
    fun cutoffMillis(nowMillis: Long, days: Int): Long {
        require(days >= 0) { "days must be >= 0" }
        return nowMillis - TimeUnit.DAYS.toMillis(days.toLong())
    }

    /** A row with no deletion timestamp is never eligible for age-based cleanup. */
    fun isEligibleForAgeCleanup(deletedAt: Long?, cutoffMillis: Long): Boolean =
        deletedAt != null && deletedAt < cutoffMillis

    fun clampAutoDeleteDays(days: Int): Int = days.coerceIn(MIN_AUTO_DELETE_DAYS, MAX_AUTO_DELETE_DAYS)
}
