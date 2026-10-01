package com.propdfeditor.batch.util

/** Final state of a batch job, derived from per-file results. Pure logic (JVM-testable). */
enum class BatchOutcome {
    /** Every file succeeded (or there was nothing to do). */
    COMPLETED,

    /** At least one file succeeded and at least one failed. Stored as COMPLETED (existing model). */
    COMPLETED_WITH_ERRORS,

    /** At least one file was attempted and none succeeded. Stored as FAILED. */
    FAILED,

    /** The job was stopped. Always wins over every other outcome. */
    CANCELLED
}

object BatchOutcomeResolver {
    fun resolve(succeeded: Int, failed: Int, cancelled: Boolean): BatchOutcome = when {
        cancelled -> BatchOutcome.CANCELLED
        failed > 0 && succeeded == 0 -> BatchOutcome.FAILED
        failed > 0 -> BatchOutcome.COMPLETED_WITH_ERRORS
        else -> BatchOutcome.COMPLETED
    }
}
