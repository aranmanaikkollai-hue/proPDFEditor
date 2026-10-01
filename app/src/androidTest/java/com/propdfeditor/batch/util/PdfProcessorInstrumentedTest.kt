package com.propdfeditor.batch.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.core.domain.repository.CompressionRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar

/**
 * Instrumented tests for the PDFBox batch engine. Every assertion re-opens the SAVED output;
 * nothing is verified on in-memory objects. Not run in the authoring environment (no SDK/device).
 */
@RunWith(AndroidJUnit4::class)
class PdfProcessorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var processor: PdfProcessor

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "testpdfs").apply { deleteRecursively(); mkdirs() }
        processor = PdfProcessor(mockk<CompressionRepository>(relaxed = true))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---- fixtures

    private data class PageSpec(val width: Float, val height: Float, val rotation: Int, val text: String)

    private fun makePdf(name: String, vararg pages: PageSpec): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (spec in pages) {
                val page = PDPage(PDRectangle(spec.width, spec.height))
                page.rotation = spec.rotation
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(40f, 60f)
                    cs.showText(spec.text)
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun a4(text: String, rotation: Int = 0) = PageSpec(595f, 842f, rotation, text)
    private fun fileUri(f: File): Uri = Uri.fromFile(f)
    private fun contentUri(f: File): Uri = Uri.parse("content://com.propdfeditor.test.pdfs/${f.name}")
    private fun text(f: File, password: String = ""): String =
        PDDocument.load(f, password).use { PDFTextStripper().getText(it) }
    private fun sortedText(f: File): String =
        PDDocument.load(f).use { d -> PDFTextStripper().also { it.sortByPosition = true }.getText(d) }
    private fun pageCount(f: File): Int = PDDocument.load(f).use { it.numberOfPages }

    // ---- rotate

    @Test
    fun rotate_mixedAndAlreadyRotatedPages_addsToExistingRotation() = runBlocking {
        val src = makePdf("in.pdf", a4("p1"), a4("p2", 90), PageSpec(842f, 595f, 270, "p3"))
        val out = File(dir, "out.pdf")
        processor.rotatePdf(context, fileUri(src), fileUri(out), 90, null)
        PDDocument.load(out).use {
            assertEquals(90, it.getPage(0).rotation)
            assertEquals(180, it.getPage(1).rotation)
            assertEquals(0, it.getPage(2).rotation) // 270 + 90
        }
    }

    @Test
    fun rotate_pageSubsetAndCounterClockwise() = runBlocking {
        val src = makePdf("in.pdf", a4("p1"), a4("p2"), a4("p3"))
        val out = File(dir, "out.pdf")
        processor.rotatePdf(context, fileUri(src), fileUri(out), -90, listOf(2, 99))
        PDDocument.load(out).use {
            assertEquals(0, it.getPage(0).rotation)
            assertEquals(270, it.getPage(1).rotation)
            assertEquals(0, it.getPage(2).rotation)
        }
    }

    @Test
    fun rotate_worksWithContentUris() = runBlocking {
        val src = makePdf("cin.pdf", a4("hello"))
        val out = File(dir, "cout.pdf").apply { createNewFile() }
        processor.rotatePdf(context, contentUri(src), contentUri(out), 180, null)
        PDDocument.load(out).use { assertEquals(180, it.getPage(0).rotation) }
        assertTrue(text(out).contains("hello"))
    }

    // ---- merge / split

    @Test
    fun merge_preservesInputOrderPageCountAndPageSizes() = runBlocking {
        val a = makePdf("a.pdf", a4("alpha1"), a4("alpha2"))
        val b = makePdf("b.pdf", PageSpec(842f, 595f, 0, "beta1"))
        val out = File(dir, "merged.pdf")
        val progress = mutableListOf<Int>()
        processor.mergePdfs(context, listOf(fileUri(b), fileUri(a)), fileUri(out)) { progress.add(it) }
        assertEquals(listOf(1, 2), progress)
        assertEquals(3, pageCount(out))
        val t = text(out)
        assertTrue(t.indexOf("beta1") < t.indexOf("alpha1"))
        assertTrue(t.indexOf("alpha1") < t.indexOf("alpha2"))
        PDDocument.load(out).use { assertEquals(842f, it.getPage(0).mediaBox.width, 0.5f) }
    }

    @Test
    fun merge_failure_doesNotTouchOutput() = runBlocking {
        val a = makePdf("a.pdf", a4("alpha"))
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        val out = File(dir, "merged.pdf").apply { writeText("KEEP") }
        try {
            processor.mergePdfs(context, listOf(fileUri(a), fileUri(bad)), fileUri(out)) { }
            fail("expected failure")
        } catch (expected: Exception) {
            assertEquals("KEEP", out.readText())
        }
    }

    // ---- watermark

    @Test
    fun watermark_allAnglesAndPositions_keepPageCountAndAddText() = runBlocking {
        val src = makePdf("in.pdf", a4("body"), a4("rot90", 90), PageSpec(842f, 595f, 270, "land"))
        for (angle in listOf(0f, 45f, 90f, 180f, 270f)) {
            val out = File(dir, "wm_$angle.pdf")
            processor.watermarkPdf(
                context, fileUri(src), fileUri(out),
                WatermarkParams("CONFIDENTIAL", 48f, 0.3f, Color.GRAY, angle, "CENTER", null)
            )
            assertEquals(3, pageCount(out))
            val t = text(out)
            assertTrue("angle $angle", t.contains("CONFIDENTIAL"))
            assertTrue(t.contains("body"))
        }
        for (pos in listOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")) {
            val out = File(dir, "wm_$pos.pdf")
            processor.watermarkPdf(
                context, fileUri(src), fileUri(out),
                WatermarkParams("DRAFT", 24f, 1f, Color.RED, 0f, pos, null)
            )
            assertTrue(text(out).contains("DRAFT"))
        }
    }

    @Test
    fun watermark_unencodableCharactersAreDroppedNotFatal() = runBlocking {
        val src = makePdf("in.pdf", a4("body"))
        val out = File(dir, "out.pdf")
        processor.watermarkPdf(
            context, fileUri(src), fileUri(out),
            WatermarkParams("SECRET \u4E2D\u6587", 30f, 0.5f, Color.BLACK, 0f, "CENTER", null)
        )
        assertTrue(text(out).contains("SECRET"))
    }

    // ---- encrypt / decrypt

    private fun encrypt(src: File, out: File, level: String, print: Boolean = true, copy: Boolean = true, modify: Boolean = false) =
        runBlocking { processor.encryptPdf(context, fileUri(src), fileUri(out), EncryptParams("s3cret", print, copy, modify, level)) }

    @Test
    fun encryptDecrypt_roundTrip_allLevels() = runBlocking {
        val src = makePdf("in.pdf", a4("roundtrip"), a4("page2"))
        for (level in listOf("AES_256", "AES_128", "STANDARD")) {
            val enc = File(dir, "enc_$level.pdf")
            encrypt(src, enc, level)
            // password required
            try {
                PDDocument.load(enc).close()
                fail("$level opened without password")
            } catch (expected: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            }
            // wrong password through the processor
            try {
                processor.decryptPdf(context, fileUri(enc), fileUri(File(dir, "x.pdf")), "wrong")
                fail("wrong password accepted")
            } catch (expected: PdfPasswordException) {
            }
            val dec = File(dir, "dec_$level.pdf")
            processor.decryptPdf(context, fileUri(enc), fileUri(dec), "s3cret")
            PDDocument.load(dec).use { assertFalse(it.isEncrypted) }
            assertEquals(2, pageCount(dec))
            assertTrue(text(dec).contains("roundtrip"))
        }
    }

    @Test
    fun encrypt_permissionBitsAreWritten() {
        val src = makePdf("in.pdf", a4("perm"))
        val enc = File(dir, "enc.pdf")
        encrypt(src, enc, "AES_256", print = false, copy = false, modify = false)
        PDDocument.load(enc, "s3cret").use {
            val ap = it.currentAccessPermission
            assertFalse(ap.canPrint()); assertFalse(ap.canExtractContent()); assertFalse(ap.canModify())
        }
        val enc2 = File(dir, "enc2.pdf")
        encrypt(src, enc2, "AES_256", print = true, copy = true, modify = true)
        PDDocument.load(enc2, "s3cret").use {
            val ap = it.currentAccessPermission
            assertTrue(ap.canPrint()); assertTrue(ap.canExtractContent()); assertTrue(ap.canModify())
        }
    }

    @Test
    fun decrypt_unencryptedInput_producesReadableCopy() = runBlocking {
        val src = makePdf("in.pdf", a4("plain"))
        val out = File(dir, "out.pdf")
        processor.decryptPdf(context, fileUri(src), fileUri(out), "")
        assertTrue(text(out).contains("plain"))
    }

    @Test
    fun decrypt_worksWithContentUris() = runBlocking {
        val src = makePdf("cin.pdf", a4("viaSaf"))
        val enc = File(dir, "cenc.pdf").apply { createNewFile() }
        processor.encryptPdf(context, contentUri(src), contentUri(enc), EncryptParams("pw", true, true, false, "AES_256"))
        val dec = File(dir, "cdec.pdf").apply { createNewFile() }
        processor.decryptPdf(context, contentUri(enc), contentUri(dec), "pw")
        assertTrue(text(dec).contains("viaSaf"))
    }

    // ---- searchable PDF (invisible text layer)

    @Test
    fun searchablePdf_textExtractableAndPageCountKept() = runBlocking {
        val src = makePdf("scan.pdf", a4("visible-original"), a4("second", 90))
        val out = File(dir, "ocr.pdf")
        val page = OcrPage(1000, 1414, listOf(OcrLine("Invoice 12345", 100, 100, 500, 140)))
        processor.writeSearchablePdf(context, src, fileUri(out), listOf(page, page))
        assertEquals(2, pageCount(out))
        val t = text(out)
        assertTrue(t.contains("Invoice 12345"))
        assertTrue(t.contains("visible-original")) // original content intact
    }

    @Test
    fun searchablePdf_pageCountMismatchFails() = runBlocking {
        val src = makePdf("scan.pdf", a4("one"))
        try {
            processor.writeSearchablePdf(context, src, fileUri(File(dir, "o.pdf")), emptyList())
            fail("expected mismatch failure")
        } catch (expected: PdfOperationException) {
        }
    }

    // =====================================================================================
    // Phase 2A additions
    // =====================================================================================

    private fun imagePdf(name: String, label: String, color: Int): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val image = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(image, 100f, 400f, 200f, 200f)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(100f, 300f); cs.showText(label); cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun merge_2_3_1_pages_yields_exactly_6_in_order_file_and_content_uris() = runBlocking {
        val a = makePdf("a.pdf", a4("A1"), a4("A2"))
        val b = makePdf("b.pdf", a4("B1"), a4("B2"), a4("B3"))
        val c = makePdf("c.pdf", PageSpec(842f, 595f, 0, "C1"))
        for (useContent in listOf(false, true)) {
            val out = File(dir, "m_$useContent.pdf").apply { createNewFile() }
            val toUri: (File) -> Uri = { if (useContent) contentUri(it) else fileUri(it) }
            processor.mergePdfs(context, listOf(a, b, c).map(toUri), toUri(out)) { }
            assertEquals("no duplicated pages (content=$useContent)", 6, pageCount(out))
            val t = text(out)
            val order = listOf("A1", "A2", "B1", "B2", "B3", "C1").map { t.indexOf(it) }
            assertTrue("all present: $order", order.all { it >= 0 })
            assertEquals("input order kept", order.sorted(), order)
        }
    }

    @Test
    fun merge_keepsImagesWhenSourcesAreClosedAfterwards() = runBlocking {
        // Regression: sources closed before save => "COSStream has been closed".
        val a = imagePdf("ia.pdf", "imgA", Color.BLACK)
        val b = imagePdf("ib.pdf", "imgB", Color.DKGRAY)
        val out = File(dir, "img_merged.pdf")
        processor.mergePdfs(context, listOf(fileUri(a), fileUri(b)), fileUri(out)) { }
        assertEquals(2, pageCount(out))
        PDDocument.load(out).use { doc ->
            for (i in 0 until 2) {
                val names = doc.getPage(i).resources.xObjectNames.toList()
                assertEquals("page $i keeps its image", 1, names.size)
                assertTrue(doc.getPage(i).resources.getXObject(names[0]) is
                    com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)
            }
        }
    }

    @Test
    fun merge_encryptedInputIsRejectedAndOutputUntouched() = runBlocking {
        val a = makePdf("a.pdf", a4("A"))
        val enc = File(dir, "enc.pdf")
        processor.encryptPdf(context, fileUri(a), fileUri(enc), EncryptParams("pw", true, true, false, "AES_256"))
        val out = File(dir, "out.pdf").apply { writeText("KEEP") }
        try {
            processor.mergePdfs(context, listOf(fileUri(a), fileUri(enc)), fileUri(out)) { }
            fail("expected rejection")
        } catch (expected: PdfOperationException) {
            assertEquals("KEEP", out.readText())
        }
    }

    @Test
    fun merge_cancelledMidway_throwsCancellationAndLeavesOutputUntouched() = runBlocking {
        val files = (1..4).map { makePdf("c$it.pdf", a4("c$it")) }
        val out = File(dir, "cancel_out.pdf").apply { writeText("KEEP") }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).let { scope ->
            scope.async {
                processor.mergePdfs(context, files.map(::fileUri), fileUri(out)) { processed ->
                    if (processed == 2) throw CancellationException("test cancel")
                }
            }
        }
        try {
            job.await()
            fail("expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("output must not be replaced by a partial merge", "KEEP", out.readText())
        }
    }

    @Test
    fun rotate_inheritsRotateFromPagesNode() = runBlocking {
        val base = makePdf("inh0.pdf", a4("i"))
        val inh = File(dir, "inh.pdf")
        PDDocument.load(base).use { d ->
            d.getPage(0).cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.ROTATE)
            d.pages.cosObject.setInt(com.tom_roush.pdfbox.cos.COSName.ROTATE, 90)
            d.save(inh)
        }
        val out = File(dir, "inh_out.pdf")
        processor.rotatePdf(context, fileUri(inh), fileUri(out), 90, null)
        PDDocument.load(out).use { assertEquals(180, it.getPage(0).rotation) }
    }

    @Test
    fun rotate_45DegreesIsRejected_onlyQuarterTurnsAreValidPdfRotation() = runBlocking {
        val src = makePdf("in.pdf", a4("x"))
        try {
            processor.rotatePdf(context, fileUri(src), fileUri(File(dir, "o.pdf")), 45, null)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun watermark_onSignedPdf_isRefusedAndWritesNothing() = runBlocking {
        val plain = makePdf("plain.pdf", a4("sign me"))
        val signed = File(dir, "signed.pdf")
        PDDocument.load(plain).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only")
                setSignDate(Calendar.getInstance())
            }
            // Test fixture only: dummy CMS bytes, no real key material.
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            FileOutputStream(File(dir, "signed_tmp.pdf")).use { doc.saveIncremental(it) }
        }
        File(dir, "signed_tmp.pdf").copyTo(signed, overwrite = true)
        val out = File(dir, "wm_signed.pdf")
        try {
            processor.watermarkPdf(
                context, fileUri(signed), fileUri(out),
                WatermarkParams("X", 20f, 1f, Color.BLACK, 0f, "CENTER", null)
            )
            fail("expected SignedPdfException")
        } catch (expected: SignedPdfException) {
            assertFalse(out.exists() && out.length() > 0)
        }
    }

    @Test
    fun encrypt_nonAsciiPasswords_aes256RoundTrips_lowerLevelsRefuseNonLatin1() = runBlocking {
        val src = makePdf("in.pdf", a4("tamil"))
        val tamil = "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD123"
        val enc = File(dir, "tamil_256.pdf")
        processor.encryptPdf(context, fileUri(src), fileUri(enc), EncryptParams(tamil, true, true, false, "AES_256"))
        // must NOT open with a lossy '?' substitute
        try {
            PDDocument.load(enc, "?????123").close()
            fail("opened with lossy substitute")
        } catch (expected: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        }
        val dec = File(dir, "tamil_dec.pdf")
        processor.decryptPdf(context, fileUri(enc), fileUri(dec), tamil)
        assertTrue(text(dec).contains("tamil"))

        for (level in listOf("AES_128", "STANDARD")) {
            try {
                processor.encryptPdf(context, fileUri(src), fileUri(File(dir, "no_$level.pdf")),
                    EncryptParams(tamil, true, true, false, level))
                fail("$level must refuse a non-Latin-1 password")
            } catch (expected: PdfOperationException) {
                assertFalse(File(dir, "no_$level.pdf").exists())
            }
        }
        // Latin-1 characters are fine at lower levels
        val latin = File(dir, "latin.pdf")
        processor.encryptPdf(context, fileUri(src), fileUri(latin), EncryptParams("caf\u00E9", true, true, false, "AES_128"))
        PDDocument.load(latin, "caf\u00E9").use { assertTrue(it.isEncrypted) }
    }

    @Test
    fun decrypt_userPasswordOfRestrictedFile_isRefused_ownerPasswordWorks() = runBlocking {
        val src = makePdf("in.pdf", a4("restricted"))
        val restricted = File(dir, "restricted.pdf")
        PDDocument.load(src).use { doc ->
            val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission().apply {
                setCanPrint(false); setCanExtractContent(false)
            }
            val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("ownerpw", "userpw", ap)
            policy.setEncryptionKeyLength(256); policy.setPreferAES(true)
            doc.protect(policy); doc.save(restricted)
        }
        try {
            processor.decryptPdf(context, fileUri(restricted), fileUri(File(dir, "u.pdf")), "userpw")
            fail("user password must not strip restrictions")
        } catch (expected: PdfOperationException) {
        }
        val ok = File(dir, "o.pdf")
        processor.decryptPdf(context, fileUri(restricted), fileUri(ok), "ownerpw")
        PDDocument.load(ok).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun searchablePdf_rotatedPages_textExtractsAndOriginalTextKept() = runBlocking {
        for (rot in listOf(0, 90, 180, 270)) {
            val media = if (rot % 180 == 0) PageSpec(595f, 842f, rot, "scanbody") else PageSpec(842f, 595f, rot, "scanbody")
            val src = makePdf("scan_$rot.pdf", media)
            val out = File(dir, "ocr_$rot.pdf")
            val page = OcrPage(1190, 1684, listOf(
                OcrLine("Invoice 12345", 100, 200, 500, 260),
                OcrLine("Total Due", 100, 800, 400, 860)
            ))
            processor.writeSearchablePdf(context, src, fileUri(out), listOf(page))
            val t = sortedText(out)
            assertTrue("rot=$rot", t.contains("Invoice 12345") && t.contains("Total Due") && t.contains("scanbody"))
            assertEquals(1, pageCount(out))
        }
    }

    @Test
    fun searchablePdf_nonWinAnsiText_isDroppedNotFatal_documentedLimitation() = runBlocking {
        // KNOWN LIMITATION: Helvetica/WinAnsi cannot encode Tamil/Hindi/Arabic. Latin part survives.
        val src = makePdf("scan.pdf", a4("body"))
        val out = File(dir, "ocr_unicode.pdf")
        val page = OcrPage(1000, 1414, listOf(OcrLine("Invoice \u0BA4\u0BAE\u0BBF\u0BB4\u0BCD 42", 100, 100, 600, 150)))
        processor.writeSearchablePdf(context, src, fileUri(out), listOf(page))
        val t = text(out)
        assertTrue(t.contains("Invoice") && t.contains("42"))
        assertFalse(t.contains("\u0BA4"))
    }
}
