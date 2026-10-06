package com.propdf.saf

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.corpus.CorpusAssets
import com.propdf.security.data.database.SecurityDatabase
import com.propdf.security.data.repository.SecurityRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
 * Phase 9 item 9: SAF matrix. For each (input scheme x output scheme) the operation must read the source, write
 * the destination, and the destination must reopen with the expected content. Schemes: file:// and content://
 * (served by TestPdfProvider, which stands in for an external document provider: it has no filesystem path the
 * app can use, exactly like a real provider). A real third-party provider (Drive, SD card, Downloads picker) is NOT
 * covered here and must be tested manually/with UI Automator. NOT RUN in the authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class SafMatrixInstrumentedTest {

    private lateinit var dir: File           // served by TestPdfProvider
    private lateinit var db: SecurityDatabase
    private lateinit var repo: SecurityRepository

    @Before fun setUp() {
        val ctx = CorpusAssets.targetContext
        PDFBoxResourceLoader.init(ctx)
        dir = CorpusAssets.providerDir().apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, SecurityDatabase::class.java).allowMainThreadQueries().build()
        repo = SecurityRepository(ctx, db.securityOperationDao(), db.redactionDao(), db.secureDocumentDao())
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private enum class Scheme { FILE, CONTENT }

    private fun uriFor(f: File, s: Scheme): Uri =
        if (s == Scheme.FILE) Uri.fromFile(f) else CorpusAssets.providerUri(f.name)

    private fun text(f: File) = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    @Test fun removeMetadata_acrossAllSchemeCombinations() = runBlocking<Unit> {
        for (inS in Scheme.values()) for (outS in Scheme.values()) {
            val tag = "${inS.name.lowercase()}_${outS.name.lowercase()}"
            val src = CorpusAssets.copyTo(dir, "06_annotated.pdf", "src_$tag.pdf")
            val dst = File(dir, "dst_$tag.pdf").apply { writeBytes(ByteArray(0)) }
            val r = repo.removeMetadata(uriFor(src, inS), uriFor(dst, outS))
            assertTrue("$tag: ${r.exceptionOrNull()}", r.isSuccess)
            assertTrue("$tag: output written", dst.length() > 0)
            PDDocument.load(dst).use { d ->
                assertEquals("$tag: pages", 3, d.numberOfPages)
                assertEquals("$tag: annotation dictionaries kept", 4, d.getPage(0).annotations.size)
            }
            assertTrue("$tag: text kept", text(dst).contains("ALPHA-7731"))
        }
    }

    @Test fun sanitize_openModifySaveReopen_overContentUri() = runBlocking<Unit> {
        val src = CorpusAssets.copyTo(dir, "01_normal.pdf", "san_src.pdf")
        val dst = File(dir, "san_dst.pdf").apply { writeBytes(ByteArray(0)) }
        val r = repo.sanitizeDocument(CorpusAssets.providerUri(src.name), CorpusAssets.providerUri(dst.name))
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertTrue(text(dst).contains("BRAVO-4410"))
    }

    @Test fun secureDelete_rejectsContentUri_andLeavesTheFile() = runBlocking<Unit> {
        val f = CorpusAssets.copyTo(dir, "01_normal.pdf", "keep.pdf")
        val before = CorpusAssets.sha256(f)
        val r = repo.secureDelete(CorpusAssets.providerUri(f.name))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
        assertTrue(f.exists()); assertEquals(before, CorpusAssets.sha256(f))
    }

    @Test fun secureDelete_rejectsFileOutsideAppStorage_andPathTraversal() = runBlocking<Unit> {
        val outside = File.createTempFile("outside_", ".pdf", CorpusAssets.targetContext.cacheDir.parentFile)  // app data root, not a whitelisted dir
        try {
            outside.writeText("x")
            assertTrue(repo.secureDelete(Uri.fromFile(outside)).isFailure)
            val traversal = File(CorpusAssets.targetContext.cacheDir, "../${outside.name}")
            assertTrue(repo.secureDelete(Uri.fromFile(traversal)).isFailure)
            assertTrue("file must survive", outside.exists())
        } finally { outside.delete() }
    }

    @Test fun secureDelete_overwritesAndDeletesFileInsideAppCache() = runBlocking<Unit> {
        val f = File(CorpusAssets.targetContext.cacheDir, "to_delete_${System.nanoTime()}.pdf").apply { writeBytes(ByteArray(2048) { 7 }) }
        val r = repo.secureDelete(Uri.fromFile(f))
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertFalse(f.exists())
    }

    @Test fun malformedAndEncryptedInputs_overContentUri_failCleanly() = runBlocking<Unit> {
        for (name in listOf("15_malformed_zero_bytes.pdf", "15_malformed_garbage_after_header.pdf", "04_encrypted_aes256.pdf")) {
            val src = CorpusAssets.copyTo(dir, name, "bad_$name")
            val dst = File(dir, "bad_out_$name").apply { writeBytes(ByteArray(0)) }
            val r = repo.removeMetadata(CorpusAssets.providerUri(src.name), CorpusAssets.providerUri(dst.name))
            assertTrue("$name must fail", r.isFailure)
            assertEquals("$name: destination untouched", 0L, dst.length())
        }
    }
}
