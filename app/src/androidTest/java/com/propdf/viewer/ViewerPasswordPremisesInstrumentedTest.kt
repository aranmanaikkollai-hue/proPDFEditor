package com.propdf.viewer

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.corpus.CorpusAssets
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies, on a real Android PdfRenderer, the premises the viewer's password flow is built on (Phase 9):
 *  1. PdfRenderer throws SecurityException for a user-password PDF (this used to be reported as "File access has
 *     expired"),
 *  2. PDFBox distinguishes it (InvalidPasswordException with the empty password),
 *  3. the correct password yields a decrypted copy PdfRenderer can open; a wrong password does not,
 *  4. owner-restricted PDFs with an empty user password open directly (no prompt needed).
 * NOT RUN in the authoring environment (no device).
 */
@RunWith(AndroidJUnit4::class)
class ViewerPasswordPremisesInstrumentedTest {

    private lateinit var dir: File

    @Before fun setUp() {
        PDFBoxResourceLoader.init(CorpusAssets.targetContext)
        dir = CorpusAssets.workDir("viewerpw")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun pageCount(file: File): Int {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { return PdfRenderer(fd).use { it.pageCount } } finally { fd.close() }
    }

    @Test fun pdfRenderer_rejectsUserPasswordPdf_withSecurityException() {
        for (name in listOf("04_encrypted_aes256.pdf", "04_encrypted_aes128.pdf")) {
            val f = CorpusAssets.copyTo(dir, name)
            try { pageCount(f); fail("$name should not open without a password") }
            catch (e: SecurityException) { /* expected: this is the signal the viewer must treat as "password required" */ }
        }
    }

    @Test fun pdfBox_classifiesTheSamePdfAsPasswordRequired() {
        val f = CorpusAssets.copyTo(dir, "04_encrypted_aes256.pdf")
        try { PDDocument.load(f, "").close(); fail("expected InvalidPasswordException") }
        catch (e: InvalidPasswordException) { /* expected */ }
    }

    @Test fun correctPassword_producesCopyPdfRendererCanOpen() {
        for (name in listOf("04_encrypted_aes256.pdf", "04_encrypted_aes128.pdf")) {
            val src = CorpusAssets.copyTo(dir, name)
            val out = File(dir, "unlocked_$name")
            PDDocument.load(src, CorpusAssets.USER_PW).use { d -> d.isAllSecurityToBeRemoved = true; d.save(out) }
            assertEquals(3, pageCount(out))
            assertTrue("original untouched and still encrypted",
                runCatching { pageCount(src) }.exceptionOrNull() is SecurityException)
        }
    }

    @Test fun wrongPassword_isRejected() {
        val f = CorpusAssets.copyTo(dir, "04_encrypted_aes256.pdf")
        try { PDDocument.load(f, "not-the-password").close(); fail("expected InvalidPasswordException") }
        catch (e: InvalidPasswordException) { /* expected */ }
    }

    @Test fun ownerRestrictedPdfWithEmptyUserPassword_opensWithoutPrompt() {
        val f = CorpusAssets.copyTo(dir, "04_owner_restricted_no_user_pw.pdf")
        assertEquals(3, pageCount(f))
        assertFalse(PDDocument.load(f, "").use { it.currentAccessPermission.isOwnerPermission })
    }
}
