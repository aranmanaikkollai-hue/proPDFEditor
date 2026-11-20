package com.propdfeditor.pdfedit

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.corpus.CorpusAssets
import com.propdf.pdfedit.convert.ConvertException
import com.propdf.pdfedit.convert.ConvertWarning
import com.propdf.pdfedit.convert.DirectorySink
import com.propdf.pdfedit.convert.ImageFormat
import com.propdf.pdfedit.convert.OutputSink
import com.propdf.pdfedit.convert.PageSelection
import com.propdf.pdfedit.convert.PageSeparator
import com.propdf.pdfedit.convert.PdfToImagesConverter
import com.propdf.pdfedit.convert.PdfToImagesOptions
import com.propdf.pdfedit.convert.PdfToTextConverter
import com.propdf.pdfedit.convert.PdfToTextOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * PDF to image and PDF to text conversion on the permanent regression corpus (assets/corpus).
 *
 * Behaviour tests assert on the device. exportVerificationSet() additionally writes real conversion output plus a
 * manifest.json into the instrumentation's additional test output folder, where tools/conversion/verify_conversion.py
 * compares it with Poppler (pdftoppm, pdftotext) in CI.
 */
@RunWith(AndroidJUnit4::class)
class PdfConversionInstrumentedTest {

    private lateinit var work: File
    private lateinit var input: File
    private val toImages = PdfToImagesConverter()
    private val toText = PdfToTextConverter()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(CorpusAssets.targetContext)
        work = CorpusAssets.workDir("convert")
        input = File(work, "in").apply { mkdirs() }
    }

    private fun pdf(name: String): File = CorpusAssets.copyTo(input, name)
    private fun sink(label: String) = DirectorySink(File(work, label))
    private fun tmp() = File(work, "tmp")
    private fun filesIn(label: String): List<String> = File(work, label).list().orEmpty().sorted()

    private fun images(name: String, options: PdfToImagesOptions, label: String, password: String? = null) = runBlocking {
        toImages.convert(pdf(name), name, options, sink(label), tmp(), password)
    }

    private fun text(name: String, options: PdfToTextOptions, label: String, password: String? = null) = runBlocking {
        toText.convert(pdf(name), name, options, sink(label), password)
    }

    private fun expectReason(reason: ConvertException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected " + reason)
        } catch (e: ConvertException) {
            assertEquals(reason, e.reason)
        }
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return Pair(o.outWidth, o.outHeight)
    }

    // ---- images ----------------------------------------------------------------------------------------------

    @Test
    fun everyPageBecomesAnImageNamedInPageOrder() {
        val report = images("01_normal.pdf", PdfToImagesOptions(dpi = 72), "a")
        assertEquals(listOf("01_normal_page_001.png", "01_normal_page_002.png", "01_normal_page_003.png"), report.outputs)
        assertEquals(report.outputs, filesIn("a"))
        assertEquals(3, report.pagesConverted)
        assertEquals(3, report.totalPages)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun pageRangesSelectPagesAndKeepTheirRealNumbers() {
        val report = images("02_large_300pages.pdf", PdfToImagesOptions(dpi = 36, pages = PageSelection.Ranges("2,299-300")), "b")
        assertEquals(
            listOf("02_large_300pages_page_002.png", "02_large_300pages_page_299.png", "02_large_300pages_page_300.png"),
            report.outputs
        )
        assertEquals(300, report.totalPages)
    }

    @Test
    fun imagesHaveAnOpaqueWhiteBackground() {
        images("01_normal.pdf", PdfToImagesOptions(dpi = 72, pages = PageSelection.Ranges("1")), "c")
        val bmp = BitmapFactory.decodeFile(File(work, "c/01_normal_page_001.png").path)
        assertEquals(Color.WHITE, bmp.getPixel(0, 0))
        assertEquals(Color.WHITE, bmp.getPixel(bmp.width - 1, bmp.height - 1))
        bmp.recycle()
    }

    @Test
    fun jpegOutputIsAJpegFile() {
        val report = images("03_scanned_2pages.pdf", PdfToImagesOptions(format = ImageFormat.JPEG, dpi = 72, jpegQuality = 80), "d")
        assertEquals(2, report.outputs.size)
        val head = File(work, "d/" + report.outputs[0]).readBytes().take(3)
        assertEquals(listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), head)
    }

    @Test
    fun rotatedPagesComeOutTheWayTheyAreDisplayed() {
        val report = images("08_rotated_pages.pdf", PdfToImagesOptions(dpi = 36), "e")
        assertEquals(4, report.outputs.size)
        val sizes = report.outputs.map { bounds(File(work, "e/" + it)) }
        assertTrue("page 1 is portrait", sizes[0].second > sizes[0].first)
        assertTrue("page 2 (rotate 90) is landscape", sizes[1].first > sizes[1].second)
        assertTrue("page 3 (rotate 180) is portrait", sizes[2].second > sizes[2].first)
        assertTrue("page 4 (rotate 270) is landscape", sizes[3].first > sizes[3].second)
    }

    @Test
    fun theResolutionIsReducedToStayWithinThePixelLimit() {
        val report = images("01_normal.pdf", PdfToImagesOptions(dpi = 600, maxPixels = 500_000L, pages = PageSelection.Ranges("1")), "f")
        assertTrue(report.warnings.contains(ConvertWarning.RESOLUTION_REDUCED))
        assertTrue(report.minEffectiveDpi < 600.0)
        val (w, h) = bounds(File(work, "f/" + report.outputs[0]))
        assertTrue("$w x $h exceeds the limit", w.toLong() * h <= 500_000L)
    }

    @Test
    fun aHugeImagePageConvertsWithoutRunningOutOfMemory() {
        val report = images("14_large_image.pdf", PdfToImagesOptions(dpi = 72), "g")
        assertEquals(1, report.outputs.size)
        assertTrue(File(work, "g/" + report.outputs[0]).length() > 0)
    }

    @Test
    fun sourceFileIsNeverModified() {
        val file = pdf("01_normal.pdf")
        val before = CorpusAssets.sha256(file)
        runBlocking { toImages.convert(file, "x", PdfToImagesOptions(dpi = 36), sink("h"), tmp()) }
        runBlocking { toText.convert(file, "x", PdfToTextOptions(), sink("h2")) }
        assertEquals(before, CorpusAssets.sha256(file))
    }

    @Test
    fun invalidPageListsAreRejectedBeforeAnythingIsWritten() {
        expectReason(ConvertException.Reason.INVALID_RANGE) {
            images("01_normal.pdf", PdfToImagesOptions(pages = PageSelection.Ranges("1-9")), "i")
        }
        assertTrue(filesIn("i").isEmpty())
        expectReason(ConvertException.Reason.INVALID_RANGE) {
            text("01_normal.pdf", PdfToTextOptions(pages = PageSelection.Ranges("3-1")), "i2")
        }
        assertTrue(filesIn("i2").isEmpty())
    }

    @Test
    fun cancellingRemovesEveryFileThatWasAlreadyWritten() {
        val dir = File(work, "cancel")
        val file = pdf("02_large_300pages.pdf")
        runBlocking {
            val job = launch(Dispatchers.Default) {
                toImages.convert(file, "big", PdfToImagesOptions(dpi = 100), DirectorySink(dir), tmp())
            }
            var waited = 0
            while (dir.list().orEmpty().isEmpty() && waited < 600) {
                delay(50)
                waited++
            }
            assertTrue("conversion never wrote a file", dir.list().orEmpty().isNotEmpty())
            job.cancelAndJoin()
        }
        assertTrue("left behind: " + dir.list().orEmpty().toList(), dir.list().orEmpty().isEmpty())
    }

    @Test
    fun aFailingWriteRemovesTheFilesWrittenBefore() {
        val inner = sink("j")
        var opened = 0
        val failing = object : OutputSink {
            override fun open(name: String, mimeType: String): OutputStream {
                if (++opened == 2) throw IOException("disk full")
                return inner.open(name, mimeType)
            }

            override fun delete(name: String) = inner.delete(name)
        }
        expectReason(ConvertException.Reason.WRITE_FAILED) {
            runBlocking { toImages.convert(pdf("01_normal.pdf"), "x", PdfToImagesOptions(dpi = 36), failing, tmp()) }
        }
        assertTrue(filesIn("j").isEmpty())
    }

    // ---- passwords and permissions ---------------------------------------------------------------------------

    @Test
    fun encryptedFileNeedsItsPasswordForImagesAndText() {
        expectReason(ConvertException.Reason.PASSWORD_REQUIRED) { images("04_encrypted_aes256.pdf", PdfToImagesOptions(dpi = 36), "k") }
        expectReason(ConvertException.Reason.PASSWORD_REQUIRED) { text("04_encrypted_aes256.pdf", PdfToTextOptions(), "k2") }
        expectReason(ConvertException.Reason.WRONG_PASSWORD) { images("04_encrypted_aes256.pdf", PdfToImagesOptions(dpi = 36), "k3", "nope") }
        expectReason(ConvertException.Reason.WRONG_PASSWORD) { text("04_encrypted_aes256.pdf", PdfToTextOptions(), "k4", "nope") }
        assertTrue(filesIn("k").isEmpty() && filesIn("k2").isEmpty() && filesIn("k3").isEmpty() && filesIn("k4").isEmpty())
    }

    @Test
    fun encryptedFileConvertsWithTheRightPasswordAndLeavesNoDecryptedCopy() {
        val imgs = images("04_encrypted_aes256.pdf", PdfToImagesOptions(dpi = 36), "l", CorpusAssets.USER_PW)
        assertEquals(3, imgs.pagesConverted)
        val txt = text("04_encrypted_aes256.pdf", PdfToTextOptions(), "l2", CorpusAssets.USER_PW)
        assertEquals(3, txt.pagesConverted)
        assertTrue(File(work, "l2/04_encrypted_aes256.txt").readText().contains("ALPHA-7731"))
        assertTrue("decrypted temp copy must be deleted", tmp().list().orEmpty().isEmpty())
    }

    @Test
    fun aFileThatForbidsTextExtractionIsRefusedForTextButNotForImages() {
        expectReason(ConvertException.Reason.NOT_PERMITTED) { text("04_owner_restricted_no_user_pw.pdf", PdfToTextOptions(), "m") }
        assertTrue(filesIn("m").isEmpty())
        val imgs = images("04_owner_restricted_no_user_pw.pdf", PdfToImagesOptions(dpi = 36), "m2")
        assertEquals(3, imgs.pagesConverted)
    }

    // ---- damaged files ---------------------------------------------------------------------------------------

    @Test
    fun brokenFilesFailCleanlyWithoutLeavingOutput() {
        for (name in listOf("15_malformed_zero_bytes.pdf", "15_malformed_not_a_pdf.pdf", "15_malformed_garbage_after_header.pdf")) {
            expectReason(ConvertException.Reason.UNREADABLE) { images(name, PdfToImagesOptions(dpi = 36), "n_" + name) }
            expectReason(ConvertException.Reason.UNREADABLE) { text(name, PdfToTextOptions(), "nt_" + name) }
            assertTrue(filesIn("n_" + name).isEmpty() && filesIn("nt_" + name).isEmpty())
        }
    }

    @Test
    fun aTruncatedFileEitherConvertsOrFailsWithAConvertExceptionAndNoLeftovers() {
        try {
            images("15_malformed_truncated.pdf", PdfToImagesOptions(dpi = 36), "o")
        } catch (e: ConvertException) {
            assertTrue(filesIn("o").isEmpty())
        }
        try {
            text("15_malformed_truncated.pdf", PdfToTextOptions(), "o2")
        } catch (e: ConvertException) {
            assertTrue(filesIn("o2").isEmpty())
        }
    }

    // ---- text ------------------------------------------------------------------------------------------------

    @Test
    fun textContainsTheKnownMarkersAndOneFormFeedPerPage() {
        val report = text("01_normal.pdf", PdfToTextOptions(), "p")
        val content = File(work, "p/01_normal.txt").readText(Charsets.UTF_8)
        for (marker in listOf("ALPHA-7731", "BRAVO-4410", "CHARLIE-9082")) assertTrue(marker, content.contains(marker))
        assertEquals(3, content.count { it == '\u000C' })
        assertEquals(listOf("01_normal.txt"), report.outputs)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun headerLineSeparatorNamesEveryPage() {
        text("01_normal.pdf", PdfToTextOptions(separator = PageSeparator.HEADER_LINE), "q")
        val content = File(work, "q/01_normal.txt").readText()
        for (n in 1..3) assertTrue(content.contains("--- Page " + n + " ---"))
        assertTrue(!content.contains('\u000C'))
    }

    @Test
    fun unicodeTextSurvivesTheRoundTrip() {
        // Tamil: exact text. Arabic: the corpus says extraction order of unshaped right-to-left text is not
        // guaranteed, so the characters are compared as a set. CJK uses non-embedded predefined fonts whose
        // extraction depends on bundled CMaps: it must not crash, but the text is not asserted here (the Poppler
        // comparison in CI reports it).
        fun strip(s: String) = s.replace(Regex("\\s+"), "")
        for (name in listOf("10_unicode_tamil.pdf", "11_unicode_arabic.pdf", "12_unicode_cjk.pdf")) {
            val markers = CorpusAssets.manifestEntry(name).getJSONArray("text_contains")
            text(name, PdfToTextOptions(), "r_" + name)
            val content = strip(File(work, "r_" + name + "/" + name.removeSuffix(".pdf") + ".txt").readText(Charsets.UTF_8))
            if (name.startsWith("12_")) continue
            for (i in 0 until markers.length()) {
                val m = strip(markers.getString(i))
                if (name.startsWith("11_")) {
                    val available = content.groupingBy { it }.eachCount()
                    for ((ch, n) in m.groupingBy { it }.eachCount()) {
                        assertTrue(name + " lacks " + ch, (available[ch] ?: 0) >= n)
                    }
                } else {
                    assertTrue(name + " lacks " + m, content.contains(m))
                }
            }
        }
    }

    @Test
    fun aScannedDocumentGivesAnEmptyTextFileAndAWarning() {
        val report = text("03_scanned_2pages.pdf", PdfToTextOptions(), "s")
        assertTrue(report.warnings.contains(ConvertWarning.NO_TEXT_LAYER))
        assertEquals(2, report.pagesWithoutText)
        assertTrue(File(work, "s/03_scanned_2pages.txt").readText().isBlank())
    }

    @Test
    fun textPageRangesAreHonoured() {
        val report = text("02_large_300pages.pdf", PdfToTextOptions(pages = PageSelection.Ranges("1,300")), "t")
        assertEquals(2, report.pagesConverted)
        val content = File(work, "t/02_large_300pages.txt").readText()
        assertTrue(content.contains("LG-001-00") && content.contains("LG-300-39"))
        assertTrue(!content.contains("LG-150-"))
    }

    // ---- output for the independent Poppler check ------------------------------------------------------------

    private fun verifyDir(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val candidates = listOfNotNull(
            arg?.takeIf { it.isNotEmpty() }?.let { File(it) },
            File(CorpusAssets.targetContext.getExternalFilesDir(null), "additional_test_output")
        )
        for (base in candidates) {
            try {
                val dir = File(base, "conversion-verify")
                dir.deleteRecursively()
                if (dir.mkdirs() || dir.isDirectory) return dir
            } catch (e: Exception) {
                // try the next location
            }
        }
        return File(work, "conversion-verify").apply { mkdirs() }
    }

    @Test
    fun exportVerificationSet() {
        val out = verifyDir()
        val entries = JSONArray()
        val pw = CorpusAssets.USER_PW

        fun imageEntry(id: String, source: String, options: PdfToImagesOptions, pages: List<Int>, spec: String?, password: String? = null) {
            val dir = File(out, id)
            val report = runBlocking { toImages.convert(pdf(source), source, options, DirectorySink(dir), tmp(), password) }
            assertEquals(pages.size, report.outputs.size)
            for (name in report.outputs) assertTrue(File(dir, name).length() > 0)
            entries.put(
                JSONObject()
                    .put("id", id).put("kind", "images").put("source", source)
                    .put("format", options.format.extension).put("dpi", options.dpi)
                    .put("max_pixels", options.maxPixels).put("pages", JSONArray(pages)).put("page_spec", spec ?: JSONObject.NULL)
                    .put("files", JSONArray(report.outputs)).put("password", password ?: JSONObject.NULL)
                    .put("reduced", report.warnings.contains(ConvertWarning.RESOLUTION_REDUCED))
            )
        }

        fun textEntry(id: String, source: String, options: PdfToTextOptions, pages: List<Int>, sep: String, password: String? = null) {
            val dir = File(out, id)
            val report = runBlocking { toText.convert(pdf(source), source, options, DirectorySink(dir), password) }
            assertEquals(1, report.outputs.size)
            entries.put(
                JSONObject()
                    .put("id", id).put("kind", "text").put("source", source).put("separator", sep)
                    .put("pages", JSONArray(pages)).put("file", report.outputs[0]).put("password", password ?: JSONObject.NULL)
                    .put("no_text_layer", report.warnings.contains(ConvertWarning.NO_TEXT_LAYER))
            )
        }

        imageEntry("img_normal_png_100", "01_normal.pdf", PdfToImagesOptions(dpi = 100), listOf(1, 2, 3), null)
        imageEntry("img_rotated_png_72", "08_rotated_pages.pdf", PdfToImagesOptions(dpi = 72), listOf(1, 2, 3, 4), null)
        imageEntry("img_mixed_png_72", "09_mixed_sizes.pdf", PdfToImagesOptions(dpi = 72), listOf(1, 2, 3, 4, 5), null)
        imageEntry("img_scanned_jpg_100", "03_scanned_2pages.pdf", PdfToImagesOptions(format = ImageFormat.JPEG, dpi = 100, jpegQuality = 85), listOf(1, 2), null)
        imageEntry("img_largeimage_png_72", "14_large_image.pdf", PdfToImagesOptions(dpi = 72), listOf(1), null)
        imageEntry("img_300p_ranges_png_50", "02_large_300pages.pdf", PdfToImagesOptions(dpi = 50, pages = PageSelection.Ranges("1-2,299-300")), listOf(1, 2, 299, 300), "1-2,299-300")
        imageEntry("img_normal_capped", "01_normal.pdf", PdfToImagesOptions(dpi = 600, maxPixels = 2_000_000L, pages = PageSelection.Ranges("1")), listOf(1), "1")
        imageEntry("img_encrypted_png_50", "04_encrypted_aes256.pdf", PdfToImagesOptions(dpi = 50), listOf(1, 2, 3), null, pw)

        textEntry("txt_normal", "01_normal.pdf", PdfToTextOptions(), listOf(1, 2, 3), "FORM_FEED")
        textEntry("txt_normal_header", "01_normal.pdf", PdfToTextOptions(separator = PageSeparator.HEADER_LINE), listOf(1, 2, 3), "HEADER_LINE")
        textEntry("txt_tamil", "10_unicode_tamil.pdf", PdfToTextOptions(), listOf(1), "FORM_FEED")
        textEntry("txt_arabic", "11_unicode_arabic.pdf", PdfToTextOptions(), listOf(1), "FORM_FEED")
        textEntry("txt_cjk", "12_unicode_cjk.pdf", PdfToTextOptions(), listOf(1), "FORM_FEED")
        textEntry("txt_fonts", "13_embedded_fonts_mixed.pdf", PdfToTextOptions(), listOf(1), "FORM_FEED")
        textEntry("txt_rotated", "08_rotated_pages.pdf", PdfToTextOptions(), listOf(1, 2, 3, 4), "FORM_FEED")
        textEntry("txt_scanned", "03_scanned_2pages.pdf", PdfToTextOptions(), listOf(1, 2), "FORM_FEED")
        textEntry("txt_300p_ranges", "02_large_300pages.pdf", PdfToTextOptions(pages = PageSelection.Ranges("1-3,298-300")), listOf(1, 2, 3, 298, 299, 300), "FORM_FEED")
        textEntry("txt_encrypted", "04_encrypted_aes256.pdf", PdfToTextOptions(), listOf(1, 2, 3), "FORM_FEED", pw)

        File(out, "manifest.json").writeText(JSONObject().put("version", 1).put("entries", entries).toString(2))
        assertTrue(File(out, "manifest.json").length() > 0)
    }
}
