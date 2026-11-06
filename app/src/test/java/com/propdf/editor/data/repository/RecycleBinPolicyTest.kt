package com.propdf.editor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecycleBinPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun cutoff_30days_isInThePast_notOverflowed() {
        val cutoff = RecycleBinPolicy.cutoffMillis(now, 30)
        assertEquals(now - 30 * day, cutoff)
        assertTrue(cutoff < now)
    }

    @Test fun cutoff_neverMovesIntoTheFuture_forAnyAllowedSetting() {
        for (d in 0..365) assertTrue("days=$d", RecycleBinPolicy.cutoffMillis(now, d) <= now)
    }

    @Test fun recentItem_isNotEligible_oldItemIs() {
        val cutoff = RecycleBinPolicy.cutoffMillis(now, 30)
        assertFalse(RecycleBinPolicy.isEligibleForAgeCleanup(now - 10 * day, cutoff))
        assertTrue(RecycleBinPolicy.isEligibleForAgeCleanup(now - 40 * day, cutoff))
    }

    @Test fun itemWithoutDeletionTimestamp_isNeverEligible() {
        assertFalse(RecycleBinPolicy.isEligibleForAgeCleanup(null, RecycleBinPolicy.cutoffMillis(now, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDays_isRejected() { RecycleBinPolicy.cutoffMillis(now, -1) }

    @Test fun autoDeleteDays_isClamped() {
        assertEquals(1, RecycleBinPolicy.clampAutoDeleteDays(0))
        assertEquals(90, RecycleBinPolicy.clampAutoDeleteDays(10_000))
        assertEquals(30, RecycleBinPolicy.clampAutoDeleteDays(30))
    }

    @Test fun itemExactlyAtThreshold_isRetained_strictlyOlderIsEligible() {
        val cutoff = RecycleBinPolicy.cutoffMillis(now, 30)
        assertFalse(RecycleBinPolicy.isEligibleForAgeCleanup(cutoff, cutoff))
        assertTrue(RecycleBinPolicy.isEligibleForAgeCleanup(cutoff - 1, cutoff))
    }

    @Test fun defaultAndLimits_matchOwnerDecision() {
        assertEquals(1, RecycleBinPolicy.MIN_AUTO_DELETE_DAYS)
        assertEquals(90, RecycleBinPolicy.MAX_AUTO_DELETE_DAYS)
    }
}
