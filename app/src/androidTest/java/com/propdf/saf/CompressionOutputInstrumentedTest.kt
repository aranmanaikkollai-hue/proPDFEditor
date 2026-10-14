package com.propdf.saf

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.data.repository.PdfBoxCompressionRepository
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.result.AppResult
import com.propdf.core.saf.SafHelper
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.corpus.CorpusAssets
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
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
 * Phase 1: compression output must be published safely. Matrix of input x output scheme (file://, content://
 * via TestPdfProvider), failure leaves the existing destination untouched, no .part or temp files remain, and a
 * content:// preview does not leak its cache copy. A real third-party SAF provider is NOT covered.
 * Instrumented; NOT RUN in the authoring environment (no SDK/device). The DispatcherProvider import path and
 * CompressionHistoryDao mock mirror PdfProcessorSignedAndCompressionTest.
 */
@RunWith(AndroidJUnit4::class)
class CompressionOutputInstrumentedTest {

    private lateinit var dir: File
    private lateinit var repo: PdfBoxCompressionRepository
    private val ctx get() = CorpusAssets.targetContext

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before fun setUp() {
        PDFBoxResourceLoader.init(ctx)
        dir = CorpusAssets.providerDir().apply { deleteRecursively(); mkdirs() }
        repo = PdfBoxCompressionRepository(ctx, dispatchers, SafHelper(ctx), mockk(relaxed = true))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun makePdf(name: String, pages: Int = 2): File {
        val f = File(dir, name)
        PDDocument().use { d ->
            repeat(pages) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 700f)
                    cs.showText("Compression output page ${i + 1}"); cs.endText()
                }
            }
            d.save(f)
        }
        return f
    }

    private fun uri(f: File, content: Boolean): Uri = if (content) CorpusAssets.providerUri(f.name) else Uri.fromFile(f)

    private fun run(src: Uri, dst: Uri): AppResult<*> = runBlocking {
        repo.compress(src.toString(), dst.toString(), CompressionConfig()).toList().last { it !is AppResult.Loading }
    }

    private fun leftovers() =
        (dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().toList() +
            ctx.cacheDir.listFiles { f -> f.name.startsWith("compressed_") || f.name.startsWith("pdf_src_") }.orEmpty().toList())

    private fun pageCount(f: File) = PDDocument.load(f).use { it.numberOfPages }

    private fun matrix(inContent: Boolean, outContent: Boolean) {
        val src = makePdf("in_${inContent}_$outContent.pdf", pages = 3)
        val dst = File(dir, "out_${inContent}_$outContent.pdf")
        // An existing LARGER destination must be fully replaced (truncation), not partially overwritten.
        dst.writeBytes(ByteArray(2_000_000) { 0x41 })
        if (outContent) { /* provider serves the same directory */ }
        val r = run(uri(src, inContent), uri(dst, outContent))
        assertTrue("$r", r is AppResult.Success<*>)
        assertEquals(3, pageCount(dst))
        assertTrue("destination must be the new PDF, not stale bytes (size=${dst.length()})", dst.length() < 1_000_000)
        assertTrue(leftovers().toString(), leftovers().isEmpty())
    }

    @Test fun fileToFile() = matrix(false, false)
    @Test fun contentToFile() = matrix(true, false)
    @Test fun fileToContent() = matrix(false, true)
    @Test fun contentToContent() = matrix(true, true)

    @Test fun corruptSource_failsAndLeavesExistingDestinationIntact() {
        val bad = File(dir, "bad.pdf").apply { writeText("%PDF-1.4 this is not a real pdf") }
        val dst = makePdf("keep.pdf", pages = 2)
        val before = CorpusAssets.sha256(dst)
        val r = run(Uri.fromFile(bad), Uri.fromFile(dst))
        assertTrue("$r", r is AppResult.Error)
        assertEquals("existing destination must be untouched", before, CorpusAssets.sha256(dst))
        assertTrue(leftovers().toString(), leftovers().isEmpty())
    }

    @Test fun sequentialRuns_useDistinctTempFiles_andLeaveNothingBehind() {
        repeat(3) { i ->
            val src = makePdf("seq$i.pdf"); val dst = File(dir, "seq_out$i.pdf")
            assertTrue(run(Uri.fromFile(src), Uri.fromFile(dst)) is AppResult.Success<*>)
            assertEquals(2, pageCount(dst))
        }
        assertTrue(leftovers().toString(), leftovers().isEmpty())
    }

    @Test fun contentPreview_doesNotLeakItsCacheCopy() = runBlocking<Unit> {
        val src = makePdf("prev.pdf")
        repo.preview(CorpusAssets.providerUri(src.name).toString(), CompressionConfig())
        assertFalse(ctx.cacheDir.listFiles { f -> f.name.startsWith("pdf_src_") }.orEmpty().isNotEmpty())
    }
}
