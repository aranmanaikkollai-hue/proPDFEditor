package com.propdf.editor.ui

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The editor replaces the user's document only after the result is validated and the original is
 * backed up. File-scheme URIs are used here; `content://` write behaviour of third-party providers
 * is not covered (NOT RUN, and not simulated). Android instrumented tests; NOT RUN in the
 * authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class SafeDocumentReplacerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "replacer").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.listFiles()?.forEach { it.setWritable(true); }
        dir.deleteRecursively()
        leftovers().forEach { it.delete() }
    }

    private fun pdf(name: String, pages: Int): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4)) }
            doc.save(f)
        }
        return f
    }

    private fun leftovers(): List<File> =
        context.cacheDir.listFiles { f -> f.name.startsWith("replace_new_") || f.name.startsWith("replace_backup_") }
            ?.toList().orEmpty()

    private fun ok(file: File) = PdfResultValidator.validate(file, null)

    // ---- PdfResultValidator -------------------------------------------------------------------

    @Test
    fun validator_acceptsReadablePdf_andChecksPageCount() {
        val f = pdf("three.pdf", 3)
        assertNull(PdfResultValidator.validate(f, null))
        assertNull(PdfResultValidator.validate(f, 3))
        assertNotNull(PdfResultValidator.validate(f, 4))
    }

    @Test
    fun validator_rejectsEmptyMissingAndNonPdf() {
        assertNotNull(PdfResultValidator.validate(File(dir, "missing.pdf"), null))
        assertNotNull(PdfResultValidator.validate(File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }, null))
        assertNotNull(PdfResultValidator.validate(File(dir, "junk.pdf").apply { writeText("not a pdf at all") }, null))
    }

    // ---- SafeDocumentReplacer -----------------------------------------------------------------

    @Test
    fun replace_success_writesNewContent_andLeavesNoTemporaryFiles() {
        val target = pdf("target.pdf", 2)
        val newPdf = pdf("new.pdf", 5)
        val outcome = SafeDocumentReplacer.replace(context, Uri.fromFile(target), Uri.fromFile(newPdf), ::ok)
        assertEquals(SafeDocumentReplacer.Outcome.Replaced, outcome)
        assertArrayEquals(newPdf.readBytes(), target.readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun replace_validationFailure_leavesOriginalUntouched() {
        val target = pdf("target2.pdf", 2)
        val before = target.readBytes()
        val bad = File(dir, "bad.pdf").apply { writeText("garbage") }
        val outcome = SafeDocumentReplacer.replace(context, Uri.fromFile(target), Uri.fromFile(bad), ::ok)
        assertTrue(outcome is SafeDocumentReplacer.Outcome.Failed)
        outcome as SafeDocumentReplacer.Outcome.Failed
        assertTrue(outcome.originalIntact)
        assertArrayEquals(before, target.readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun replace_pageCountMismatch_leavesOriginalUntouched() {
        val target = pdf("target3.pdf", 2)
        val before = target.readBytes()
        val fewer = pdf("fewer.pdf", 1)
        val outcome = SafeDocumentReplacer.replace(context, Uri.fromFile(target), Uri.fromFile(fewer)) {
            PdfResultValidator.validate(it, expectedPages = 2)
        }
        assertTrue(outcome is SafeDocumentReplacer.Outcome.Failed)
        assertArrayEquals(before, target.readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun replace_missingNewContent_leavesOriginalUntouched() {
        val target = pdf("target4.pdf", 2)
        val before = target.readBytes()
        val outcome = SafeDocumentReplacer.replace(
            context, Uri.fromFile(target), Uri.fromFile(File(dir, "nope.pdf")), ::ok
        )
        assertTrue(outcome is SafeDocumentReplacer.Outcome.Failed)
        assertArrayEquals(before, target.readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun replace_targetNotWritable_reportsUnchangedOriginal_andKeepsNoBackup() {
        val target = pdf("readonly.pdf", 2)
        val before = target.readBytes()
        val newPdf = pdf("new2.pdf", 3)
        assertTrue(target.setReadOnly())
        val outcome = SafeDocumentReplacer.replace(context, Uri.fromFile(target), Uri.fromFile(newPdf), ::ok)
        assertTrue("expected failure, got $outcome", outcome is SafeDocumentReplacer.Outcome.Failed)
        outcome as SafeDocumentReplacer.Outcome.Failed
        assertTrue("original must be reported intact", outcome.originalIntact)
        assertNull(outcome.backup)
        assertArrayEquals(before, target.readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun replace_missingTarget_failsWithoutChangingAnything() {
        val newPdf = pdf("new3.pdf", 3)
        val outcome = SafeDocumentReplacer.replace(
            context, Uri.fromFile(File(dir, "gone.pdf")), Uri.fromFile(newPdf), ::ok
        )
        assertTrue(outcome is SafeDocumentReplacer.Outcome.Failed)
        assertFalse(File(dir, "gone.pdf").exists())
        assertTrue(leftovers().isEmpty())
    }
}
