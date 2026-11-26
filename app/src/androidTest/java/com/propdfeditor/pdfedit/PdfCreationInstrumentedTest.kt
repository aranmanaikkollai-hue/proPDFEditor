package com.propdfeditor.pdfedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.corpus.CorpusAssets
import com.propdf.pdfedit.convert.ConvertException
import com.propdf.pdfedit.convert.ConvertWarning
import com.propdf.pdfedit.convert.DirectorySink
import com.propdf.pdfedit.convert.ImagesToPdfConverter
import com.propdf.pdfedit.convert.ImagesToPdfOptions
import com.propdf.pdfedit.convert.PageMode
import com.propdf.pdfedit.convert.TextToPdfConverter
import com.propdf.pdfedit.convert.TextToPdfOptions
import com.propdf.pdfedit.ops.FontChoice
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Images to PDF and text to PDF. Behaviour tests assert on the device (including rendering the produced pages with the
 * platform PdfRenderer). exportVerificationSet() also writes real output plus manifest.json for
 * tools/conversion/verify_conversion.py, which checks it with qpdf, pdfinfo, pdffonts, pdftoppm and pdftotext in CI.
 */
@RunWith(AndroidJUnit4::class)
class PdfCreationInstrumentedTest {

    private lateinit var work: File
    private lateinit var input: File
    private lateinit var toPdf: ImagesToPdfConverter
    private lateinit var textToPdf: TextToPdfConverter

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PDFBoxResourceLoader.init(instrumentation.targetContext)
        toPdf = ImagesToPdfConverter()
        textToPdf = TextToPdfConverter(instrumentation.targetContext) // the bundled fonts are assets of :pdfedit, packaged in the app APK
        work = CorpusAssets.workDir("create")
        input = File(work, "in").apply { mkdirs() }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun tmp() = File(work, "tmp")
    private fun sink(label: String) = DirectorySink(File(work, label))
    private fun filesIn(label: String): List<String> = File(work, label).list().orEmpty().sorted()

    /** Top-left red, top-right green, bottom-left blue, bottom-right yellow, with a black diagonal. */
    private fun quadrants(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, w / 2f, h / 2f, p)
        p.color = Color.GREEN; c.drawRect(w / 2f, 0f, w.toFloat(), h / 2f, p)
        p.color = Color.BLUE; c.drawRect(0f, h / 2f, w / 2f, h.toFloat(), p)
        p.color = Color.YELLOW; c.drawRect(w / 2f, h / 2f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    private fun savePng(bmp: Bitmap, name: String): File {
        val f = File(input, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun saveJpeg(bmp: Bitmap, name: String, orientation: Int = 1): File {
        val f = File(input, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != 1) {
            val exif = ExifInterface(f.path)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return f
    }

    private fun expectReason(reason: ConvertException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected " + reason)
        } catch (e: ConvertException) {
            assertEquals(reason, e.reason)
        }
    }

    private fun convert(images: List<File>, label: String, options: ImagesToPdfOptions = ImagesToPdfOptions()) =
        runBlocking { toPdf.convert(images, "out", options, sink(label), tmp()) }

    private fun convertText(file: File, label: String, options: TextToPdfOptions = TextToPdfOptions()) =
        runBlocking { textToPdf.convert(file, "doc", options, sink(label), tmp()) }

    private fun textFile(name: String, content: String): File = File(input, name).apply { writeText(content, Charsets.UTF_8) }

    private fun extract(f: File): String = PDDocument.load(f).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), "")

    private fun render(pdf: File, pageIndex: Int = 0): Bitmap {
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }

    private fun near(expected: Int, actual: Int, tolerance: Int = 90): Boolean =
        abs(Color.red(expected) - Color.red(actual)) < tolerance &&
            abs(Color.green(expected) - Color.green(actual)) < tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) < tolerance

    // ---- images to PDF ---------------------------------------------------------------------------------------

    @Test
    fun imagesBecomePagesInOrderWithPlannedSizes() {
        val a = savePng(quadrants(300, 400), "a.png")
        val b = saveJpeg(quadrants(400, 300), "b.jpg")
        val c = savePng(quadrants(300, 300), "c.png")
        val report = convert(listOf(a, b, c), "p1")
        assertEquals(listOf("out.pdf"), report.outputs)
        assertEquals(3, report.pagesConverted)
        PDDocument.load(File(work, "p1/out.pdf")).use { doc ->
            assertEquals(3, doc.numberOfPages)
            val s = (0..2).map { doc.getPage(it).mediaBox }
            assertEquals(595.2756f, s[0].width, 0.01f); assertEquals(841.8898f, s[0].height, 0.01f)
            assertEquals(841.8898f, s[1].width, 0.01f); assertEquals(595.2756f, s[1].height, 0.01f)
            assertEquals(595.2756f, s[2].width, 0.01f)
        }
    }

    @Test
    fun matchImageModeUsesThePictureSizeAtTheGivenResolution() {
        val a = savePng(quadrants(300, 400), "a.png")
        convert(listOf(a), "p2", ImagesToPdfOptions(pageMode = PageMode.MATCH_IMAGE, matchDpi = 150))
        PDDocument.load(File(work, "p2/out.pdf")).use { doc ->
            assertEquals(144f, doc.getPage(0).mediaBox.width, 0.01f)
            assertEquals(192f, doc.getPage(0).mediaBox.height, 0.01f)
        }
    }

    @Test
    fun allEightExifOrientationsComeOutUpright() {
        val r = Color.RED; val g = Color.GREEN; val b = Color.BLUE; val y = Color.YELLOW
        // visual top-left, top-right, bottom-left, bottom-right
        val expected = mapOf(
            1 to listOf(r, g, b, y), 2 to listOf(g, r, y, b), 3 to listOf(y, b, g, r), 4 to listOf(b, y, r, g),
            5 to listOf(r, b, g, y), 6 to listOf(b, r, y, g), 7 to listOf(y, g, b, r), 8 to listOf(g, y, r, b)
        )
        for ((orientation, colors) in expected) {
            val file = saveJpeg(quadrants(400, 200), "o$orientation.jpg", orientation)
            convert(listOf(file), "o$orientation", ImagesToPdfOptions(pageMode = PageMode.MATCH_IMAGE, matchDpi = 72))
            val bmp = render(File(work, "o$orientation/out.pdf"))
            val w = bmp.width; val h = bmp.height
            val points = listOf(Pair(w / 4, h / 4), Pair(3 * w / 4, h / 4), Pair(w / 4, 3 * h / 4), Pair(3 * w / 4, 3 * h / 4))
            val names = listOf("top-left", "top-right", "bottom-left", "bottom-right")
            for (i in 0..3) {
                val px = bmp.getPixel(points[i].first, points[i].second)
                assertTrue(
                    "orientation $orientation $" + names[i] + " expected " + Integer.toHexString(colors[i]) + " got " + Integer.toHexString(px),
                    near(colors[i], px)
                )
            }
            assertEquals("orientation $orientation page width", if (orientation >= 5) 200 else 400, w)
            bmp.recycle()
        }
    }

    @Test
    fun aJpegIsEmbeddedWithoutRecompression() {
        val file = saveJpeg(quadrants(400, 200), "photo.jpg", 6)
        convert(listOf(file), "p3")
        PDDocument.load(File(work, "p3/out.pdf")).use { doc ->
            val res = doc.getPage(0).resources
            val name = res.xObjectNames.first()
            val image = res.getXObject(name) as PDImageXObject
            assertEquals("jpg", image.suffix)
            val raw = image.cosObject.createRawInputStream().use { it.readBytes() }
            assertTrue("embedded JPEG differs from the original", raw.contentEquals(file.readBytes()))
        }
    }

    @Test
    fun aTransparentPngIsDrawnOnAWhitePage() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        Canvas(bmp).drawRect(80f, 80f, 120f, 120f, Paint().apply { color = Color.RED })
        val file = savePng(bmp, "alpha.png")
        convert(listOf(file), "p4", ImagesToPdfOptions(pageMode = PageMode.MATCH_IMAGE, matchDpi = 72))
        val page = render(File(work, "p4/out.pdf"))
        assertEquals(Color.WHITE, page.getPixel(5, 5))
        assertTrue(near(Color.RED, page.getPixel(100, 100)))
        page.recycle()
    }

    @Test
    fun theMarginStaysWhite() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val file = savePng(bmp, "red.png")
        convert(listOf(file), "p5", ImagesToPdfOptions(pageMode = PageMode.A4, marginPts = 40.0))
        val page = render(File(work, "p5/out.pdf"))
        val cy = page.height / 2
        assertEquals("inside the margin", Color.WHITE, page.getPixel(36, cy))
        assertTrue("just inside the picture", near(Color.RED, page.getPixel(46, cy)))
        assertTrue("just inside the picture (right)", near(Color.RED, page.getPixel(page.width - 47, cy)))
        assertEquals("inside the right margin", Color.WHITE, page.getPixel(page.width - 36, cy))
        page.recycle()
    }

    @Test
    fun aBrokenImageFailsTheWholeJobAndLeavesNothing() {
        val good = savePng(quadrants(100, 100), "good.png")
        val bad = File(input, "bad.png").apply { writeBytes(ByteArray(500) { (it * 7).toByte() }) }
        expectReason(ConvertException.Reason.UNREADABLE) { convert(listOf(good, bad), "p6") }
        assertTrue(filesIn("p6").isEmpty())
        assertTrue(tmp().list().orEmpty().isEmpty())
    }

    @Test
    fun noImagesIsRefused() {
        expectReason(ConvertException.Reason.EMPTY_SELECTION) { convert(emptyList(), "p7") }
    }

    @Test
    fun sourceImagesAreNeverModified() {
        val file = saveJpeg(quadrants(200, 100), "keep.jpg", 6)
        val before = CorpusAssets.sha256(file)
        convert(listOf(file), "p8")
        assertEquals(before, CorpusAssets.sha256(file))
    }

    @Test
    fun aLargePngIsReducedToThePixelLimitAndSaysSo() {
        val big = Bitmap.createBitmap(5000, 4000, Bitmap.Config.RGB_565)
        big.eraseColor(Color.CYAN)
        val file = savePng(big, "big.png")
        big.recycle()
        val report = convert(listOf(file), "p9", ImagesToPdfOptions(maxPixels = 2_000_000L))
        assertTrue(report.warnings.contains(ConvertWarning.IMAGE_DOWNSAMPLED))
        PDDocument.load(File(work, "p9/out.pdf")).use { doc ->
            val image = doc.getPage(0).resources.let { it.getXObject(it.xObjectNames.first()) as PDImageXObject }
            assertTrue(image.width.toLong() * image.height <= 2_000_000L)
        }
    }

    @Test
    fun cancellingRemovesTheOutputAndTemporaryFiles() {
        val bmp = quadrants(2400, 1600)
        val files = (1..20).map { savePng(bmp, "c$it.png") } // lossless pages are slow enough to cancel mid-way
        bmp.recycle()
        val dir = File(work, "p10")
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                toPdf.convert(files, "out", ImagesToPdfOptions(), DirectorySink(dir), tmp()) { done, _ ->
                    if (done >= 2) started.complete(Unit)
                }
            }
            started.await()
            job.cancelAndJoin()
        }
        assertTrue("left behind: " + dir.list().orEmpty().toList(), dir.list().orEmpty().isEmpty())
        assertTrue("temporary files left: " + tmp().list().orEmpty().toList(), tmp().list().orEmpty().isEmpty())
    }

    // ---- text to PDF -----------------------------------------------------------------------------------------

    private val sample: String = (1..60).joinToString("\n") { n ->
        if (n % 10 == 0) "" else "Line $n: The quick brown fox jumps over the lazy dog, again and again, until the sentence is long enough to wrap around the page."
    } + "\n" + "Supercalifragilisticexpialidocious".repeat(4)

    @Test
    fun textSurvivesAsExtractableText() {
        val file = textFile("a.txt", sample)
        val report = convertText(file, "t1")
        assertEquals(listOf("doc.pdf"), report.outputs)
        assertEquals(sample.replace(Regex("\\s+"), ""), extract(File(work, "t1/doc.pdf")))
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun aLongTextBecomesSeveralPages() {
        val file = textFile("long.txt", (1..300).joinToString("\n") { "Line %03d some words here".format(it) })
        val report = convertText(file, "t2")
        assertTrue("pages: " + report.pagesConverted, report.pagesConverted >= 5)
        PDDocument.load(File(work, "t2/doc.pdf")).use { assertEquals(report.pagesConverted, it.numberOfPages) }
    }

    @Test
    fun textStaysInsideTheMargins() {
        val file = textFile("m.txt", sample)
        val report = convertText(file, "t3", TextToPdfOptions(marginPts = 54.0))
        for (pageIndex in 0 until report.pagesConverted) {
            val page = render(File(work, "t3/doc.pdf"), pageIndex)
            fun whiteRegion(x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
                for (y in y0 until y1) for (x in x0 until x1) if (page.getPixel(x, y) != Color.WHITE) return false
                return true
            }
            assertTrue("page $pageIndex left margin", whiteRegion(0, 50, 0, page.height))
            assertTrue("page $pageIndex right margin", whiteRegion(page.width - 50, page.width, 0, page.height))
            assertTrue("page $pageIndex top margin", whiteRegion(0, page.width, 0, 50))
            assertTrue("page $pageIndex bottom margin", whiteRegion(0, page.width, page.height - 50, page.height))
            page.recycle()
        }
    }

    @Test
    fun fontsAreEmbedded() {
        convertText(textFile("f.txt", "Hello world"), "t4")
        PDDocument.load(File(work, "t4/doc.pdf")).use { doc ->
            val res = doc.getPage(0).resources
            assertTrue(res.fontNames.iterator().hasNext())
            for (name in res.fontNames) assertTrue(res.getFont(name).isEmbedded)
        }
    }

    @Test
    fun utf16AndWindows1252FilesAreDecoded() {
        val utf16 = File(input, "u16.txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Caf\u00E9 \u00FCber".toByteArray(Charsets.UTF_16LE))
        }
        val r1 = convertText(utf16, "t5")
        assertTrue(r1.warnings.isEmpty())
        assertTrue(extract(File(work, "t5/doc.pdf")).contains("Caf\u00E9\u00FCber"))

        val cp = File(input, "cp.txt").apply { writeBytes("caf".toByteArray() + byteArrayOf(0xE9.toByte())) }
        val r2 = convertText(cp, "t6")
        assertTrue(r2.warnings.contains(ConvertWarning.ENCODING_GUESSED))
        assertTrue(extract(File(work, "t6/doc.pdf")).contains("caf\u00E9"))
    }

    @Test
    fun charactersTheFontLacksBecomeQuestionMarksAndAreReported() {
        val report = convertText(textFile("cjk.txt", "Hello \u4E2D world"), "t7")
        assertTrue(report.warnings.contains(ConvertWarning.CHARACTERS_REPLACED))
        assertTrue(extract(File(work, "t7/doc.pdf")).contains("Hello?world"))
    }

    @Test
    fun emptyOrBlankTextIsRefused() {
        expectReason(ConvertException.Reason.EMPTY_SELECTION) { convertText(textFile("e1.txt", ""), "t8") }
        expectReason(ConvertException.Reason.EMPTY_SELECTION) { convertText(textFile("e2.txt", "  \n \n\t"), "t9") }
        assertTrue(filesIn("t8").isEmpty() && filesIn("t9").isEmpty())
    }

    @Test
    fun boldFontAndLetterSizeWork() {
        val report = convertText(textFile("b.txt", sample), "t10", TextToPdfOptions(pageMode = PageMode.LETTER, font = FontChoice.BundledBold, fontSizePt = 14.0))
        assertTrue(report.pagesConverted >= 1)
        PDDocument.load(File(work, "t10/doc.pdf")).use { assertEquals(612f, it.getPage(0).mediaBox.width, 0.01f) }
    }

    @Test
    fun cancellingTextConversionRemovesTheOutput() {
        val file = textFile("huge.txt", (1..60000).joinToString("\n") { "Line $it of a very long document with enough words to fill the line" })
        val dir = File(work, "t11")
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) {
                textToPdf.convert(file, "doc", TextToPdfOptions(), DirectorySink(dir), tmp()) { done, _ ->
                    if (done >= 2) started.complete(Unit)
                }
            }
            started.await()
            job.cancelAndJoin()
        }
        assertTrue("left behind: " + dir.list().orEmpty().toList(), dir.list().orEmpty().isEmpty())
    }

    // ---- output for the independent check --------------------------------------------------------------------

    private fun verifyDir(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val candidates = listOfNotNull(
            arg?.takeIf { it.isNotEmpty() }?.let { File(it) },
            File(CorpusAssets.targetContext.getExternalFilesDir(null), "additional_test_output")
        )
        for (base in candidates) {
            try {
                val dir = File(base, "creation-verify")
                dir.deleteRecursively()
                if (dir.mkdirs() || dir.isDirectory) return dir
            } catch (e: Exception) {
                // try the next location
            }
        }
        return File(work, "creation-verify").apply { mkdirs() }
    }

    @Test
    fun exportVerificationSet() {
        val out = verifyDir()
        val entries = JSONArray()

        fun photo(w: Int, h: Int): Bitmap {
            val bmp = quadrants(w, h)
            val c = Canvas(bmp)
            val p = Paint().apply { color = Color.BLACK; strokeWidth = 4f; isAntiAlias = true }
            c.drawLine(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.textSize = h / 8f
            c.drawText("TEST", w / 8f, h / 2f, p)
            return bmp
        }

        fun imageEntry(id: String, files: List<File>, options: ImagesToPdfOptions, modeName: String) {
            val dir = File(out, id)
            val inputs = File(dir, "inputs").apply { mkdirs() }
            val copies = files.mapIndexed { i, f -> File(inputs, "%03d_%s".format(i + 1, f.name)).also { f.copyTo(it, overwrite = true) } }
            val report = runBlocking { toPdf.convert(copies, "result", options, DirectorySink(dir), tmp()) }
            entries.put(
                JSONObject().put("id", id).put("kind", "images_to_pdf").put("output", report.outputs[0])
                    .put("inputs", JSONArray(copies.map { "inputs/" + it.name }))
                    .put("page_mode", modeName).put("margin", options.marginPts).put("match_dpi", options.matchDpi)
            )
        }

        fun textEntry(id: String, file: File, options: TextToPdfOptions, modeName: String, lossy: Boolean = false) {
            val dir = File(out, id)
            dir.mkdirs()
            val copy = File(dir, "source.txt").also { file.copyTo(it, overwrite = true) }
            val report = runBlocking { textToPdf.convert(copy, "result", options, DirectorySink(dir), tmp()) }
            entries.put(
                JSONObject().put("id", id).put("kind", "text_to_pdf").put("output", report.outputs[0]).put("input", "source.txt")
                    .put("page_mode", modeName).put("margin", options.marginPts).put("font_size", options.fontSizePt)
                    .put("pages", report.pagesConverted).put("lossy", lossy)
            )
        }

        val a = savePng(photo(600, 800), "p_portrait.png")
        val b = saveJpeg(photo(800, 600), "p_orient6.jpg", 6)
        val alpha = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.TRANSPARENT)
            Canvas(it).drawCircle(150f, 150f, 100f, Paint().apply { color = Color.MAGENTA })
        }
        val c = savePng(alpha, "p_alpha.png")
        val d = saveJpeg(photo(640, 480), "p_orient3.jpg", 3)
        val e = saveJpeg(photo(640, 480), "p_orient8.jpg", 8)
        val f = saveJpeg(photo(500, 700), "p_orient5.jpg", 5)

        imageEntry("i2p_a4", listOf(a, b, c, d), ImagesToPdfOptions(pageMode = PageMode.A4, marginPts = 18.0), "A4")
        imageEntry("i2p_letter_margin36", listOf(e, a), ImagesToPdfOptions(pageMode = PageMode.LETTER, marginPts = 36.0), "LETTER")
        imageEntry("i2p_match_150", listOf(a, f), ImagesToPdfOptions(pageMode = PageMode.MATCH_IMAGE, matchDpi = 150), "MATCH_IMAGE")

        textEntry("t2p_latin_a4", textFile("x1.txt", sample), TextToPdfOptions(), "A4")
        textEntry("t2p_letter_bold", textFile("x2.txt", sample), TextToPdfOptions(pageMode = PageMode.LETTER, font = FontChoice.BundledBold, fontSizePt = 14.0, marginPts = 72.0), "LETTER")
        textEntry(
            "t2p_cp1252",
            File(input, "x3.txt").apply { writeBytes("Caf".toByteArray() + byteArrayOf(0xE9.toByte()) + " ".toByteArray() + "na".toByteArray() + byteArrayOf(0xEF.toByte()) + "ve r".toByteArray() + byteArrayOf(0xE9.toByte()) + "sum".toByteArray() + byteArrayOf(0xE9.toByte())) },
            TextToPdfOptions(), "A4"
        )
        textEntry("t2p_greek_cyrillic", textFile("x4.txt", "\u0391\u03B8\u03AE\u03BD\u03B1 \u0414\u043E\u0431\u0440\u044B\u0439 \u0434\u0435\u043D\u044C \u043C\u0438\u0440\n" + "Plain ASCII line follows.\n" + "\u03B1\u03B2\u03B3\u03B4\u03B5 ".repeat(40)), TextToPdfOptions(), "A4")

        File(out, "manifest.json").writeText(JSONObject().put("version", 1).put("entries", entries).toString(2))
        assertTrue(File(out, "manifest.json").length() > 0)
        assertFalse(entries.length() == 0)
    }
}
