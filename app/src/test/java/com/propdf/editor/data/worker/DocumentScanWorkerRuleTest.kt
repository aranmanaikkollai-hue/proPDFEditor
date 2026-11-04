package com.propdf.editor.data.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hourly scan must never overwrite an entry the user already has (favourite flag, category and
 * last-opened time are user data). Exercises the production rule, DocumentScanWorker.shouldInsert.
 */
class DocumentScanWorkerRuleTest {

    @Test
    fun unknownPdf_isInserted() {
        assertTrue(DocumentScanWorker.shouldInsert(alreadyKnown = false))
    }

    @Test
    fun knownPdf_isNeverOverwritten() {
        assertFalse(DocumentScanWorker.shouldInsert(alreadyKnown = true))
    }
}
