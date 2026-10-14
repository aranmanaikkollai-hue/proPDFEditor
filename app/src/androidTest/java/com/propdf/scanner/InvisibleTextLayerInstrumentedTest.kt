package com.propdf.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.pdf.text.InvisibleTextLayer
import com.propdf.corpus.CorpusAssets
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1: the invisible OCR text layer. Deterministic (synthetic OCR lines, no ML Kit): the layer must be
 * extractable after save/reopen, positioned where the OCR box was, and must not change how the page looks.
 * Instrumented; NOT RUN in the authoring environment (no SDK/device).
 */
@RunWith(AndroidJUnit4::class)
class InvisibleTextLayerInstrumentedTest {

    private lateinit var dir: File
    private val identity = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    @Before fun setUp() {
        PDFBoxResourceLoader.init(CorpusAssets.targetContext)
        dir = CorpusAssets.workDir("invisible_text")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** One A4-ish page with a solid grey rectangle so "visually intact" is measurable. */
    private fun makeScanLikePdf(): File {
        val f = File(dir, "scan.pdf")
        PDDocument().use { d ->
            val p = PDPage(PDRectangle(595f, 842f)); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                cs.setNonStrokingColor(0.6f, 0.6f, 0.6f); cs.addRect(50f, 600f, 400f, 120f); cs.fill()
            }
            d.save(f)
        }
        return f
    }

    private fun addLayer(src: File, dst: File, lines: List<InvisibleTextLayer.Line>): InvisibleTextLayer.Stats {
        PDDocument.load(src).use { d ->
            val s = InvisibleTextLayer.draw(d, d.getPage(0), lines, 595, 842, 595f, 842f, identity)
            d.save(dst)
            return s
        }
    }

    private fun render(pdf: File): Bitmap {
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
                val b = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return b
            } }
        } finally { fd.close() }
    }

    private fun text(pdf: File): String = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

    @Test fun layer_isExtractableAfterSaveAndReopen() {
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        val s = addLayer(src, dst, listOf(
            InvisibleTextLayer.Line("DELTA-5521 invoice", 60, 100, 300, 130),
            InvisibleTextLayer.Line("SCANNED PAGE", 60, 200, 260, 230)))
        assertEquals(2, s.linesDrawn); assertEquals(0, s.charactersDropped)
        val t = text(dst)
        assertTrue(t, t.contains("DELTA-5521") && t.contains("SCANNED PAGE"))
    }

    @Test fun layer_isPositionedAtTheOcrBox() {
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        addLayer(src, dst, listOf(InvisibleTextLayer.Line("ANCHOR", 100, 300, 250, 330)))
        var firstX = -1f; var firstYTopDown = -1f
        PDDocument.load(dst).use { d ->
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (text.contains("ANCHOR") && firstX < 0f) { firstX = positions[0].xDirAdj; firstYTopDown = positions[0].yDirAdj }
                }
            }.getText(d)
        }
        // x within 3pt of the box's left edge; baseline (top-down) between the box's top and bottom (300..330)
        assertEquals(100f, firstX, 3f)
        assertTrue("baseline y=$firstYTopDown", firstYTopDown in 300f..335f)
    }

    @Test fun layer_doesNotChangeTheRenderedPage() {
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        addLayer(src, dst, listOf(InvisibleTextLayer.Line("HIDDEN", 60, 100, 300, 130)))
        val a = render(src); val b = render(dst)
        try {
            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
            var diff = 0
            for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
            assertEquals("invisible layer must not alter pixels", 0, diff)
        } finally { a.recycle(); b.recycle() }
    }

    @Test fun emptyLines_drawNothing_andReportZero() {
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        val s = addLayer(src, dst, emptyList())
        assertEquals(0, s.linesDrawn)
        assertTrue(text(dst).isBlank())
    }

    @Test fun whitespaceOnlyLine_isNotCountedAsText() {
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        assertEquals(0, addLayer(src, dst, listOf(InvisibleTextLayer.Line("   ", 60, 100, 300, 130))).linesDrawn)
    }

    @Test fun unencodableCharacters_areCountedNotSilentlyLost() {
        // U+4E2D (CJK) cannot be encoded by Helvetica and system CJK fonts are .ttc (unsupported): must be reported.
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        val s = addLayer(src, dst, listOf(InvisibleTextLayer.Line("Hello \u4E2D", 60, 100, 300, 130)))
        assertTrue("dropped characters must be counted", s.charactersDropped >= 1 || text(dst).contains("\u4E2D"))
        assertTrue(text(dst).contains("Hello"))
    }

    @Test fun tamil_isExtractableWhenSystemFontExists() {
        val tamil = "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD"
        val hasFont = listOf("/system/fonts", "/product/fonts", "/system/product/fonts")
            .any { File(it, "NotoSansTamil-Regular.ttf").isFile || File(it, "NotoSansTamilUI-Regular.ttf").isFile }
        org.junit.Assume.assumeTrue("no system Tamil font on this device", hasFont)
        val src = makeScanLikePdf(); val dst = File(dir, "out.pdf")
        val s = addLayer(src, dst, listOf(InvisibleTextLayer.Line("Invoice $tamil", 60, 100, 400, 140)))
        assertEquals(0, s.charactersDropped)
        val t = java.text.Normalizer.normalize(text(dst), java.text.Normalizer.Form.NFC)
        assertTrue(t, t.contains(java.text.Normalizer.normalize(tamil, java.text.Normalizer.Form.NFC)))
    }

    // ---- generator-level behaviour (does not depend on OCR finding specific words)

    private fun leftoverTemps() = CorpusAssets.targetContext.cacheDir.listFiles { f ->
        f.name.startsWith("scan_img_") || f.name.startsWith("scan_txt_")
    }.orEmpty().toList()

    @Test fun generator_blankImage_failsInsteadOfClaimingSearchable_andLeavesNoTemps() = runBlocking<Unit> {
        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
        val r = SearchablePdfGenerator(CorpusAssets.targetContext)
            .generateSearchablePdfDetailed(listOf(bmp), "propdf_test_blank_${System.nanoTime()}.pdf")
        assertTrue("a page with no recognised text must not be reported as a searchable PDF", r.isFailure)
        assertTrue(leftoverTemps().toString(), leftoverTemps().isEmpty())
        bmp.recycle()
    }

    @Test fun generator_cancellation_leavesNoTempFiles() = runBlocking<Unit> {
        val bmps = List(3) { Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE) } }
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            SearchablePdfGenerator(CorpusAssets.targetContext).generateSearchablePdfDetailed(bmps, "propdf_test_cancel_${System.nanoTime()}.pdf")
        }
        job.cancelAndJoin()
        assertTrue(leftoverTemps().toString(), leftoverTemps().isEmpty())
        bmps.forEach { it.recycle() }
    }
}
