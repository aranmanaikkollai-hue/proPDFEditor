package com.propdfeditor.batch.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchOutcomeResolverTest {

    @Test
    fun allSucceeded_isCompleted() =
        assertEquals(BatchOutcome.COMPLETED, BatchOutcomeResolver.resolve(3, 0, false))

    @Test
    fun partialSuccess_isCompletedWithErrors_whichIsStoredAsCompleted() =
        assertEquals(BatchOutcome.COMPLETED_WITH_ERRORS, BatchOutcomeResolver.resolve(2, 1, false))

    @Test
    fun allFailed_isFailed_neverCompleted() {
        assertEquals(BatchOutcome.FAILED, BatchOutcomeResolver.resolve(0, 1, false))
        assertEquals(BatchOutcome.FAILED, BatchOutcomeResolver.resolve(0, 7, false))
    }

    @Test
    fun cancelled_alwaysWins() {
        assertEquals(BatchOutcome.CANCELLED, BatchOutcomeResolver.resolve(0, 0, true))
        assertEquals(BatchOutcome.CANCELLED, BatchOutcomeResolver.resolve(3, 0, true))
        assertEquals(BatchOutcome.CANCELLED, BatchOutcomeResolver.resolve(0, 5, true))
        assertEquals(BatchOutcome.CANCELLED, BatchOutcomeResolver.resolve(2, 2, true))
    }

    @Test
    fun nothingAttempted_isCompleted_existingBehaviourKept() =
        assertEquals(BatchOutcome.COMPLETED, BatchOutcomeResolver.resolve(0, 0, false))
}
