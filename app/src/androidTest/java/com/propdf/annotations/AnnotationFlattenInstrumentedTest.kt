package com.propdf.annotations

import android.graphics.Color
import android.graphics.RectF
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.annotations.export.FlattenException
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.model.HighlightAnnotation
import com.propdf.annotations.persistence.AnnotationDatabase
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.corpus.CorpusAssets
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 9 P0: annotation flatten must not destroy existing PDF objects, must refuse signed/encrypted/unreadable
 * input, and must be atomic. Uses the permanent corpus. Instrumented; NOT RUN in the authoring environment
 * (no SDK/device).
 */
@RunWith(AndroidJUnit4::class)
class AnnotationFlattenInstrumentedTest {

    private lateinit var db: AnnotationDatabase
    private lateinit var repo: AnnotationRepository
    private lateinit var exporter: PdfAnnotationExporter
    private lateinit var dir: File

    @Before fun setUp() {
        val ctx = CorpusAssets.targetContext
        PDFBoxResourceLoader.init(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AnnotationDatabase::class.java).allowMainThreadQueries().build()
        repo = AnnotationRepository(db.annotationDao())
        exporter = PdfAnnotationExporter(ctx, repo)
        dir = CorpusAssets.workDir("flatten")
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun highlight(page: Int) = HighlightAnnotation(
        pageIndex = page, highlightType = HighlightAnnotation.HighlightType.HIGHLIGHT,
        rects = listOf(RectF(72f, 100f, 300f, 120f)), color = Color.YELLOW
    )

    private fun pending(docId: String) = runBlocking { repo.getUnflattenedAnnotations(docId) }

    private fun noPartialFiles() = assertTrue(dir.listFiles { f -> f.name.endsWith(".part") }.isNullOrEmpty())

    @Test fun flatten_keepsLinksNotesAndFreeText_andText() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "06_annotated.pdf")
        val before = CorpusAssets.sha256(input)
        val out = File(dir, "out.pdf")
        repo.saveAnnotation("doc-a", input.path, highlight(0))

        val r = exporter.flattenAnnotations(input, out, "doc-a")
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertEquals("input untouched", before, CorpusAssets.sha256(input))
        PDDocument.load(out).use { d ->
            val page = d.getPage(0)
            assertEquals("all 4 original annotation dictionaries survive", 4, page.annotations.size)
            val link = page.annotations.filterIsInstance<PDAnnotationLink>()
            assertEquals(1, link.size)
            assertTrue(PDFTextStripper().getText(d).contains("ALPHA-7731"))
        }
        assertTrue("overlay marked flattened only after success", pending("doc-a").isEmpty())
        noPartialFiles()
    }

    @Test fun flatten_keepsFormFields() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "05_form.pdf"); val out = File(dir, "out.pdf")
        repo.saveAnnotation("doc-f", input.path, highlight(0))
        exporter.flattenAnnotations(input, out, "doc-f").getOrThrow()
        PDDocument.load(out).use { d ->
            assertEquals(4, d.documentCatalog.acroForm.fields.size)
            assertEquals(4, d.getPage(0).annotations.size)   // four widgets
        }
    }

    @Test fun flatten_refusesSignedDocument_noOutput_marksNothing() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "07_signed_valid.pdf"); val out = File(dir, "out.pdf")
        val before = CorpusAssets.sha256(input)
        repo.saveAnnotation("doc-s", input.path, highlight(0))
        val e = exporter.flattenAnnotations(input, out, "doc-s").exceptionOrNull()
        assertTrue(e is FlattenException.SignedDocument)
        assertFalse(out.exists()); noPartialFiles()
        assertEquals(before, CorpusAssets.sha256(input))
        assertEquals("overlay still pending", 1, pending("doc-s").size)
    }

    @Test fun flatten_refusesEncryptedDocuments() = runBlocking<Unit> {
        for (name in listOf("04_encrypted_aes256.pdf", "04_encrypted_aes128.pdf", "04_owner_restricted_no_user_pw.pdf")) {
            val input = CorpusAssets.copyTo(dir, name); val out = File(dir, "out_$name")
            repo.saveAnnotation("doc-e-$name", input.path, highlight(0))
            val e = exporter.flattenAnnotations(input, out, "doc-e-$name").exceptionOrNull()
            assertTrue("$name -> $e", e is FlattenException.EncryptedDocument)
            assertFalse(out.exists())
        }
        noPartialFiles()
    }

    @Test fun flatten_malformedFiles_failWithTypedError_neverCrash() = runBlocking<Unit> {
        for (name in listOf("15_malformed_zero_bytes.pdf", "15_malformed_garbage_after_header.pdf", "15_malformed_not_a_pdf.pdf")) {
            val input = CorpusAssets.copyTo(dir, name); val out = File(dir, "out_$name")
            repo.saveAnnotation("doc-m-$name", input.path, highlight(0))
            val e = exporter.flattenAnnotations(input, out, "doc-m-$name").exceptionOrNull()
            assertTrue("$name -> $e", e is FlattenException.UnreadableDocument)
            assertFalse(out.exists())
        }
        noPartialFiles()
    }

    @Test fun flatten_annotationOnMissingPage_failsAndKeepsPendingMarks() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "01_normal.pdf"); val out = File(dir, "out.pdf")
        repo.saveAnnotation("doc-p", input.path, highlight(9))
        val e = exporter.flattenAnnotations(input, out, "doc-p").exceptionOrNull()
        assertTrue(e is FlattenException.InvalidPage)
        assertFalse(out.exists()); assertEquals(1, pending("doc-p").size)
    }

    @Test fun flatten_existingOutputIsReplacedOnlyOnSuccess() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "07_signed_valid.pdf")      // will be refused
        val out = File(dir, "out.pdf").apply { writeText("previous output") }
        repo.saveAnnotation("doc-x", input.path, highlight(0))
        exporter.flattenAnnotations(input, out, "doc-x")
        assertEquals("previous output must survive a failed run", "previous output", out.readText())
    }

    // ------------------------------------------------------------------ burn

    @Test fun burn_threePageDocument_producesThreeImagePages() = runBlocking<Unit> {
        val input = CorpusAssets.copyTo(dir, "01_normal.pdf"); val out = File(dir, "burn.pdf")
        repo.saveAnnotation("doc-b", input.path, highlight(1))
        val r = exporter.burnAnnotationsIntoPdf(input, out, "doc-b", dpi = 100)
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        PDDocument.load(out).use { d ->
            assertEquals("every page exactly once (old loop skipped/re-burned pages)", 3, d.numberOfPages)
            for (i in 0 until 3) {
                val res = d.getPage(i).resources
                assertTrue("page ${i + 1} holds one raster image", res.xObjectNames.count() == 1)
            }
            assertEquals("burn is destructive: no extractable text", "", PDFTextStripper().getText(d).trim())
        }
        noPartialFiles()
    }

    @Test fun burn_refusesSignedAndEncrypted() = runBlocking<Unit> {
        val signed = CorpusAssets.copyTo(dir, "07_signed_valid.pdf")
        val enc = CorpusAssets.copyTo(dir, "04_encrypted_aes256.pdf")
        assertTrue(exporter.burnAnnotationsIntoPdf(signed, File(dir, "a.pdf"), "d1").exceptionOrNull() is FlattenException.SignedDocument)
        assertTrue(exporter.burnAnnotationsIntoPdf(enc, File(dir, "b.pdf"), "d2").exceptionOrNull() is FlattenException.EncryptedDocument)
        assertFalse(File(dir, "a.pdf").exists() || File(dir, "b.pdf").exists())
    }
}
