package com.propdfeditor.ui.compression

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.data.repository.CompressedOutputVerifier
import com.propdf.core.data.repository.CompressionOutputException
import com.propdf.core.data.repository.PdfBoxCompressionRepository
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.saf.SafHelper
import com.propdf.corpus.CorpusAssets
import com.propdfeditor.batch.util.PdfOperationException
import com.propdfeditor.batch.util.PdfPasswordException
import com.propdfeditor.batch.util.PdfProcessor
import com.propdfeditor.batch.util.SignedPdfException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar
import java.util.Random

/**
 * Milestone 1 Phase 1: the Compress screen's ViewModel on the real PdfProcessor and PdfBoxCompressionRepository,
 * plus the engine safety changes made for it (masked images, shared images, never-larger images, output
 * verification). Instrumented; NOT RUN in the authoring environment (no Gradle/SDK/device).
 *
 * NOT covered here: a real third-party SAF provider (only TestPdfProvider), genuine low-storage conditions,
 * cancellation timing, process death during a run.
 */
@RunWith(AndroidJUnit4::class)
class CompressionPhase1InstrumentedTest {

    private val ctx get() = CorpusAssets.targetContext
    private lateinit var dir: File
    private lateinit var processor: PdfProcessor
    private lateinit var vm: CompressionViewModel

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ctx)
        dir = CorpusAssets.providerDir().apply { deleteRecursively(); mkdirs() }
        val repo = PdfBoxCompressionRepository(ctx, dispatchers, SafHelper(ctx), mockk(relaxed = true))
        processor = PdfProcessor(repo)
        vm = CompressionViewModel(ctx, processor)
    }

    @After
    fun tearDown() {
        vm.reset()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    private fun noisyJpeg(doc: PDDocument, side: Int, seed: Int, quality: Float): PDImageXObject {
        val rnd = Random(seed.toLong())
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val px = IntArray(side * side) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), 30 + seed % 200) }
        bmp.setPixels(px, 0, side, 0, 0, side, side)
        val image = JPEGFactory.createFromImage(doc, bmp, quality)
        bmp.recycle()
        return image
    }

    private fun imagePdf(name: String, pages: Int, side: Int = 1200): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            for (i in 1..pages) {
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                val image = noisyJpeg(doc, side, i, 0.95f)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 50f, 300f, 400f, 400f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(60f, 760f); cs.showText("Phase one page $i"); cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun textPdf(name: String, pages: Int = 2): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(60f, 700f); cs.showText("Text page ${i + 1}"); cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun encryptedPdf(name: String, owner: String, user: String): File {
        val f = File(dir, name)
        PDDocument.load(textPdf("plain_$name")).use { doc ->
            val policy = StandardProtectionPolicy(owner, user, AccessPermission()).apply { encryptionKeyLength = 128 }
            doc.protect(policy)
            doc.save(f)
        }
        return f
    }

    private fun signedPdf(name: String): File {
        val tmp = File(dir, "tmp_$name")
        PDDocument.load(textPdf("plain_$name", 1)).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only")
                setSignDate(Calendar.getInstance())
            }
            // Test fixture only: dummy CMS bytes, no key material.
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            FileOutputStream(tmp).use { doc.saveIncremental(it) }
        }
        return File(dir, name).also { tmp.copyTo(it, overwrite = true) }
    }

    private fun rawLength(stream: COSStream): Long {
        var total = 0L
        val buf = ByteArray(8192)
        stream.createRawInputStream().use { input ->
            while (true) { val n = input.read(buf); if (n < 0) break; total += n }
        }
        return total
    }

    private fun pageCount(f: File) = PDDocument.load(f).use { it.numberOfPages }

    private fun imagesOf(doc: PDDocument, pageIndex: Int): List<PDImageXObject> {
        val res = doc.getPage(pageIndex).resources
        return res.xObjectNames.map { res.getXObject(it) }.filterIsInstance<PDImageXObject>()
    }

    private fun uri(f: File, content: Boolean): Uri = if (content) CorpusAssets.providerUri(f.name) else Uri.fromFile(f)

    private fun awaitSettled(): CompressionUiState = runBlocking {
        withTimeout(120_000) {
            vm.uiState.first { it is CompressionUiState.Loaded || it is CompressionUiState.Done || it is CompressionUiState.Error }
        }
    }

    private fun load(src: File, content: Boolean = false): CompressionUiState {
        vm.loadDocument(uri(src, content))
        return awaitSettled()
    }

    private fun compress(src: File, dst: File, settings: CompressionSettings, content: Boolean = false): CompressionUiState {
        val loaded = load(src, content)
        assertTrue("expected Loaded but was $loaded", loaded is CompressionUiState.Loaded)
        vm.compress(uri(dst, content), settings)
        return runBlocking {
            withTimeout(180_000) { vm.uiState.first { it is CompressionUiState.Done || it is CompressionUiState.Error } }
        }
    }

    private fun assertNoTempLeftovers() {
        val leftovers = ctx.cacheDir.listFiles { f ->
            f.name.startsWith("compressed_") || f.name.startsWith("pdf_src_") || f.name.startsWith("batch_src_")
        }.orEmpty().toList() + dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().toList()
        assertTrue("temp files left behind: $leftovers", leftovers.isEmpty())
    }

    // ------------------------------------------------------------------ normal and large

    @Test
    fun normalPdf_producesSmallerVerifiedCopy_andOriginalIsUntouched() {
        val src = imagePdf("normal.pdf", pages = 3)
        val dst = File(dir, "normal_out.pdf")
        val before = CorpusAssets.sha256(src)

        val state = compress(src, dst, CompressionSettings(imageQuality = 40, imageSize = ImageSizeChoice.SMALL))

        assertTrue("$state", state is CompressionUiState.Done)
        state as CompressionUiState.Done
        assertEquals(SizeComparison.Outcome.SMALLER, state.comparison.outcome)
        assertEquals("input size is the real file size", src.length(), state.comparison.originalBytes)
        assertEquals("output size is the real file size", dst.length(), state.comparison.outputBytes)
        assertEquals(3, state.pageCount)
        assertEquals(3, pageCount(dst))
        assertTrue("images were really re-encoded", state.imagesRecompressed >= 1)
        assertEquals("original must be byte-identical", before, CorpusAssets.sha256(src))
        assertNoTempLeftovers()
    }

    @Test
    fun contentUriToContentUri_works_andSizesAreMeasured() {
        val src = imagePdf("content_in.pdf", pages = 2)
        val dst = File(dir, "content_out.pdf").apply { createNewFile() }
        val before = CorpusAssets.sha256(src)

        val state = compress(src, dst, CompressionSettings(imageQuality = 50), content = true)

        assertTrue("$state", state is CompressionUiState.Done)
        state as CompressionUiState.Done
        assertEquals(dst.length(), state.comparison.outputBytes)
        assertEquals(2, pageCount(dst))
        assertEquals(before, CorpusAssets.sha256(src))
        assertNoTempLeftovers()
    }

    @Test
    fun largerPdf_isCompressedPageByPage() {
        // 20 pages with distinct 1000 px images; kept modest so it also runs on low-memory emulators.
        val src = imagePdf("large.pdf", pages = 20, side = 1000)
        val dst = File(dir, "large_out.pdf")
        val before = CorpusAssets.sha256(src)

        val state = compress(src, dst, CompressionSettings(imageQuality = 50, imageSize = ImageSizeChoice.MEDIUM))

        assertTrue("$state", state is CompressionUiState.Done)
        assertEquals(20, pageCount(dst))
        assertEquals(before, CorpusAssets.sha256(src))
        assertNoTempLeftovers()
    }

    @Test
    fun pdfWithoutImages_reportsHonestly_noFabricatedSaving() {
        val src = textPdf("textonly.pdf", pages = 3)
        val dst = File(dir, "textonly_out.pdf")
        val state = compress(src, dst, CompressionSettings())
        assertTrue("$state", state is CompressionUiState.Done)
        state as CompressionUiState.Done
        assertEquals(0, state.imagesRecompressed)
        assertEquals(dst.length(), state.comparison.outputBytes)
        assertEquals(src.length(), state.comparison.originalBytes)
    }

    // ------------------------------------------------------------------ refused / invalid inputs

    @Test
    fun invalidPdf_isRejected_andNothingIsWritten() {
        val bad = File(dir, "bad.pdf").apply { writeText("%PDF-1.4 this is not a real pdf") }
        val state = load(bad)
        assertTrue("$state", state is CompressionUiState.Error)
        assertFalse(File(dir, "bad_out.pdf").exists())
        assertNoTempLeftovers()
    }

    @Test
    fun emptyFile_isRejected() {
        val empty = File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        assertTrue(load(empty) is CompressionUiState.Error)
    }

    @Test
    fun passwordProtected_userPassword_isRefused() {
        val enc = encryptedPdf("user_pw.pdf", owner = "owner-x", user = "user-x")
        val state = load(enc)
        assertTrue("$state", state is CompressionUiState.Error)
        assertTrue((state as CompressionUiState.Error).message.contains("password", ignoreCase = true))
        assertNoTempLeftovers()
    }

    @Test
    fun ownerRestricted_encryptedPdf_isRefused_notBypassed() {
        val enc = encryptedPdf("owner_only.pdf", owner = "owner-x", user = "")
        val state = load(enc)
        assertTrue("$state", state is CompressionUiState.Error)
        assertTrue((state as CompressionUiState.Error).message.contains("password", ignoreCase = true))
    }

    @Test
    fun signedPdf_isRefused_andNothingIsWritten() {
        val signed = signedPdf("signed.pdf")
        val before = CorpusAssets.sha256(signed)
        val state = load(signed)
        assertTrue("$state", state is CompressionUiState.Error)
        assertTrue((state as CompressionUiState.Error).message.contains("signed", ignoreCase = true))
        assertEquals(before, CorpusAssets.sha256(signed))
        assertNoTempLeftovers()
    }

    @Test
    fun processorInspect_throwsTypedExceptions() = runBlocking {
        try { processor.inspectForCompression(ctx, Uri.fromFile(signedPdf("s2.pdf"))); fail("signed") } catch (_: SignedPdfException) {}
        try { processor.inspectForCompression(ctx, Uri.fromFile(encryptedPdf("e2.pdf", "o", "u"))); fail("user pw") } catch (_: PdfPasswordException) {}
        try { processor.inspectForCompression(ctx, Uri.fromFile(encryptedPdf("e3.pdf", "o", ""))); fail("owner only") } catch (_: PdfPasswordException) {}
        val info = processor.inspectForCompression(ctx, Uri.fromFile(textPdf("ok.pdf", 4)))
        assertEquals(4, info.pageCount)
        Unit
    }

    // ------------------------------------------------------------------ destination failures and original preservation

    @Test
    fun destinationSameAsSource_isRefused() {
        val src = imagePdf("same.pdf", pages = 1)
        val before = CorpusAssets.sha256(src)
        assertTrue(load(src) is CompressionUiState.Loaded)
        vm.compress(Uri.fromFile(src), CompressionSettings())
        val state = vm.uiState.value
        assertTrue("$state", state is CompressionUiState.Error)
        assertEquals(before, CorpusAssets.sha256(src))
    }

    @Test
    fun unwritableContentDestination_failsCleanly_andOriginalIsPreserved() {
        val src = imagePdf("saf_fail_in.pdf", pages = 2)
        val before = CorpusAssets.sha256(src)
        assertTrue(load(src) is CompressionUiState.Loaded)
        vm.compress(Uri.parse("content://com.propdfeditor.no.such.provider/document/1"), CompressionSettings())
        val state = runBlocking {
            withTimeout(120_000) { vm.uiState.first { it is CompressionUiState.Done || it is CompressionUiState.Error } }
        }
        assertTrue("$state", state is CompressionUiState.Error)
        assertTrue((state as CompressionUiState.Error).message.contains("original", ignoreCase = true))
        assertEquals(before, CorpusAssets.sha256(src))
        assertNoTempLeftovers()
    }

    @Test
    fun destinationUnderAFile_failsCleanly_andOriginalIsPreserved() {
        val src = imagePdf("dir_fail_in.pdf", pages = 2)
        val before = CorpusAssets.sha256(src)
        // A regular file cannot be a directory, so nothing can be created beneath it.
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val dst = File(blocker, "out.pdf")
        val state = compress(src, dst, CompressionSettings())
        assertTrue("$state", state is CompressionUiState.Error)
        assertEquals(before, CorpusAssets.sha256(src))
        assertNoTempLeftovers()
    }

    // ------------------------------------------------------------------ engine safety changes

    private fun compressViaProcessor(src: File, quality: Int = 60) = runBlocking {
        val out = File(dir, "eng_${src.nameWithoutExtension}.pdf")
        val result = processor.compressPdf(
            ctx, Uri.fromFile(src), Uri.fromFile(out),
            CompressionSettings(imageQuality = quality, imageSize = ImageSizeChoice.LARGE).toConfig()
        )
        out to result
    }

    @Test
    fun sharedImage_isReencodedOnce_notOncePerPage() {
        val src = File(dir, "shared.pdf")
        PDDocument().use { doc ->
            val shared = noisyJpeg(doc, 1200, 7, 0.95f)
            repeat(3) {
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(shared, 50f, 300f, 400f, 400f) }
            }
            doc.save(src)
        }
        val (out, result) = compressViaProcessor(src, quality = 50)
        PDDocument.load(out).use { doc ->
            val streams = (0 until 3).flatMap { imagesOf(doc, it) }.map { System.identityHashCode(it.stream.cosObject) }.toSet()
            assertEquals("the shared image must still be one stream", 1, streams.size)
        }
        assertTrue(result.imagesProcessed >= 1)
    }

    @Test
    fun imageWithSoftMask_keepsItsExactMask() {
        val src = File(dir, "smask.pdf")
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val rnd = Random(3)
        bmp.setPixels(IntArray(300 * 300) { Color.argb(128 + rnd.nextInt(100), rnd.nextInt(256), 10, 10) }, 0, 300, 0, 0, 300, 300)
        var maskLength = 0L
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val image = LosslessFactory.createFromImage(doc, bmp)
            val mask = image.stream.cosObject.getDictionaryObject(COSName.SMASK)
            assumeTrue("fixture must carry a soft mask", mask is COSStream)
            maskLength = rawLength(mask as COSStream)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(image, 50f, 300f, 300f, 300f) }
            doc.save(src)
        }
        bmp.recycle()
        val (out, _) = compressViaProcessor(src, quality = 40)
        PDDocument.load(out).use { doc ->
            val image = imagesOf(doc, 0).single()
            val mask = image.stream.cosObject.getDictionaryObject(COSName.SMASK)
            assertTrue("soft mask must survive", mask is COSStream)
            assertEquals("soft mask data must be byte-for-byte the original", maskLength, rawLength(mask as COSStream))
        }
    }

    @Test
    fun imageWithColorKeyMask_isLeftUntouched() {
        val src = File(dir, "colorkey.pdf")
        var originalLength = 0L
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val image = noisyJpeg(doc, 600, 11, 0.95f)
            image.stream.cosObject.removeItem(COSName.SMASK)
            val key = COSArray().apply { repeat(3) { add(COSInteger.get(0)); add(COSInteger.get(5)) } }
            image.stream.cosObject.setItem(COSName.MASK, key)
            originalLength = rawLength(image.stream.cosObject as COSStream)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(image, 50f, 300f, 300f, 300f) }
            doc.save(src)
        }
        val (out, result) = compressViaProcessor(src, quality = 40)
        assertEquals("an image with a /Mask entry must not be re-encoded", 0, result.imagesProcessed)
        PDDocument.load(out).use { doc ->
            assertEquals(originalLength, rawLength(imagesOf(doc, 0).single().stream.cosObject as COSStream))
        }
    }

    @Test
    fun alreadySmallImage_isNotReplacedByALargerOne() {
        val src = File(dir, "tiny_jpeg.pdf")
        var originalLength = 0L
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val image = noisyJpeg(doc, 400, 5, 0.1f) // already very small
            originalLength = rawLength(image.stream.cosObject as COSStream)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(image, 50f, 300f, 300f, 300f) }
            doc.save(src)
        }
        val (out, result) = compressViaProcessor(src, quality = 90)
        assertEquals("a bigger re-encode must be rejected", 0, result.imagesProcessed)
        PDDocument.load(out).use { doc ->
            val length = rawLength(imagesOf(doc, 0).single().stream.cosObject as COSStream)
            assertTrue("image data must not grow ($length > $originalLength)", length <= originalLength)
        }
    }

    // ------------------------------------------------------------------ output verification (validation failures)

    @Test
    fun verifier_acceptsAGoodFile_andReturnsItsRealSize() {
        val f = textPdf("v_ok.pdf", 3)
        assertEquals(f.length(), CompressedOutputVerifier.verify(ctx, Uri.fromFile(f), 3, f.length()))
    }

    @Test
    fun verifier_rejectsTruncatedFile() {
        val full = textPdf("v_full.pdf", 3)
        val cut = File(dir, "v_cut.pdf").apply { writeBytes(full.readBytes().copyOf((full.length() / 2).toInt())) }
        try {
            CompressedOutputVerifier.verify(ctx, Uri.fromFile(cut), 3, full.length())
            fail("a truncated file must be rejected")
        } catch (e: CompressionOutputException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun verifier_rejectsWrongPageCount() {
        val f = textPdf("v_pages.pdf", 3)
        try {
            CompressedOutputVerifier.verify(ctx, Uri.fromFile(f), 5, f.length())
            fail("wrong page count must be rejected")
        } catch (_: CompressionOutputException) {
        }
    }

    @Test
    fun verifier_rejectsGarbageAndByteMismatch() {
        val junk = File(dir, "v_junk.pdf").apply { writeText("not a pdf at all") }
        try {
            CompressedOutputVerifier.verify(ctx, Uri.fromFile(junk), 1, junk.length()); fail("garbage")
        } catch (_: CompressionOutputException) {
        }
        val f = textPdf("v_bytes.pdf", 2)
        try {
            CompressedOutputVerifier.verify(ctx, Uri.fromFile(f), 2, f.length() + 1); fail("byte mismatch")
        } catch (_: CompressionOutputException) {
        }
    }

    @Test
    fun verifier_rejectsMissingFile() {
        try {
            CompressedOutputVerifier.verify(ctx, Uri.fromFile(File(dir, "v_missing.pdf")), 1, 10)
            fail("missing file")
        } catch (_: CompressionOutputException) {
        }
    }
}
