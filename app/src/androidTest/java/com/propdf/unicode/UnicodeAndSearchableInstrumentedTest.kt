package com.propdf.unicode

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.annotations.export.FlattenException
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.model.TextAnnotation
import com.propdf.annotations.persistence.AnnotationDatabase
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.core.data.repository.OcrRepositoryImpl
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.corpus.CorpusAssets
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * Phase 9 item 8: Unicode and searchable-PDF verification by REOPENING the output and EXTRACTING text (never by
 * "a PDF was produced"). Tests that need a device font for a script are skipped (Assume) on devices without that
 * font, so a skip means "unverified on this device", not "passed". NOT RUN in the authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class UnicodeAndSearchableInstrumentedTest {

    private lateinit var dir: File

    private val tamil = "யாதும் ஊரே யாவரும் கேளிர்"
    private val arabic = "مرحبا بالعالم"
    private val cjk = "你好，世界 こんにちは世界 안녕하세요 세계"

    @Before fun setUp() {
        PDFBoxResourceLoader.init(CorpusAssets.targetContext)
        dir = CorpusAssets.workDir("unicode")
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
    private fun extract(f: File): String = PDDocument.load(f).use { PDFTextStripper().getText(it) }
    private fun deviceCanDraw(text: String): Boolean {
        val p = Paint()
        return text.filter { !it.isWhitespace() && it != '\u200c' && it != '\u200d' }.all { p.hasGlyph(it.toString()) }
    }

    private fun page(text: String) = OcrPageResult(
        pageIndex = 0, fullText = text, blocks = emptyList(), imageWidth = 100, imageHeight = 100,
        processingTimeMs = 0, detectedLanguages = emptyList()
    )

    private fun ocrExport(text: String): File {
        val repo = OcrRepositoryImpl(CorpusAssets.targetContext, mockk(relaxed = true))
        val out = File(dir, "ocr_export.pdf")
        val r = runBlocking { repo.exportToPdf(listOf(page(text)), Uri.fromFile(out)) }
        assertTrue("export failed: $r", r is com.propdf.core.domain.result.AppResult.Success)
        return out
    }

    // ------------------------------------------------------------------ OCR text export (Android PdfDocument)

    @Test fun ocrExport_latin_roundTrips() {
        assertTrue(extract(ocrExport("Quick brown fox LATIN-1122")).contains("LATIN-1122"))
    }

    @Test fun ocrExport_tamil_extractsSameText() {
        assumeTrue("device has no Tamil font", deviceCanDraw(tamil))
        assertEquals(nfc(tamil), nfc(extract(ocrExport(tamil)).trim()))
    }

    @Test fun ocrExport_arabic_extractsSameLetters() {
        assumeTrue("device has no Arabic font", deviceCanDraw(arabic))
        val got = nfkc(extract(ocrExport(arabic)))
        // RTL/shaped output may reorder; require every letter to be present.
        assertTrue(nfkc(arabic).filter { !it.isWhitespace() }.all { got.contains(it) })
    }

    @Test fun ocrExport_cjk_extractsSameText() {
        assumeTrue("device has no CJK font", deviceCanDraw(cjk))
        val got = nfc(extract(ocrExport(cjk)))
        for (part in cjk.split(" ")) assertTrue("missing $part", got.contains(nfc(part)))
    }

    // ------------------------------------------------------------------ corpus Unicode PDFs through PDFBox + PdfRenderer

    @Test fun corpusUnicodePdfs_extractWithPdfBox() {
        val tamilPdf = CorpusAssets.copyTo(dir, "10_unicode_tamil.pdf")
        assertTrue(nfc(extract(tamilPdf)).contains(nfc("யாதும் ஊரே யாவரும் கேளிர்")))
        val arabicPdf = CorpusAssets.copyTo(dir, "11_unicode_arabic.pdf")
        val a = nfkc(extract(arabicPdf)); assertTrue("مرحبا".all { a.contains(it) })
        val cjkPdf = CorpusAssets.copyTo(dir, "12_unicode_cjk.pdf")
        // non-embedded predefined CID fonts: depends on pdfbox-android's bundled CMaps; this documents the result
        val c = nfc(extract(cjkPdf)); assertTrue("CJK extraction via PDFBox: got '${c.take(40)}'", c.contains("你好"))
    }

    @Test fun corpusUnicodePdfs_renderNonBlank_withPdfRenderer() {
        for (name in listOf("10_unicode_tamil.pdf", "11_unicode_arabic.pdf", "12_unicode_cjk.pdf", "13_embedded_fonts_mixed.pdf")) {
            val f = CorpusAssets.copyTo(dir, name)
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var dark = 0
                    for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (Color.red(bmp.getPixel(x, y)) < 128) dark++
                    bmp.recycle()
                    assertTrue("$name rendered blank", dark > 20)
                }
            }
            fd.close()
        }
    }

    // ------------------------------------------------------------------ writing non-Latin text must never be silent

    @Test fun flatten_textAnnotationWithTamil_failsLoudly_notSilently() = runBlocking<Unit> {
        val ctx = CorpusAssets.targetContext
        val db = Room.inMemoryDatabaseBuilder(ctx, AnnotationDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = AnnotationRepository(db.annotationDao())
            val input = CorpusAssets.copyTo(dir, "01_normal.pdf"); val out = File(dir, "flat.pdf")
            repo.saveAnnotation(
                "doc-u", input.path,
                TextAnnotation(pageIndex = 0, textType = TextAnnotation.TextType.FREE_TEXT, text = tamil,
                    rect = RectF(72f, 100f, 300f, 140f), color = Color.BLACK)
            )
            val e = PdfAnnotationExporter(ctx, repo).flattenAnnotations(input, out, "doc-u").exceptionOrNull()
            assertTrue("got $e", e is FlattenException.UnsupportedCharacters)
            assertFalse(out.exists())
            assertEquals("annotation stays pending", 1, repo.getUnflattenedAnnotations("doc-u").size)
        } finally { db.close() }
    }

    @Test fun watermark_withTamil_eitherContainsTheTextOrFails_neverSilentlyDropsIt() = runBlocking<Unit> {
        val engine = com.propdf.security.watermark.WatermarkEngine(CorpusAssets.targetContext)
        val input = CorpusAssets.copyTo(dir, "01_normal.pdf"); val out = File(dir, "wm.pdf")
        val r = engine.addTextWatermark(Uri.fromFile(input), out, tamil)
        if (r.isSuccess) {
            assertTrue("success must mean the text is really in the file", nfc(extract(out)).contains(nfc(tamil)))
        } else {
            assertFalse("failure must not leave an output file", out.exists())
        }
    }

    // ------------------------------------------------------------------ searchable PDF (scanner) - text layer must be extractable

    @Test fun scannerSearchablePdf_containsExtractableOcrText() = runBlocking<Unit> {
        val ctx = CorpusAssets.targetContext
        val scanned = CorpusAssets.copyTo(dir, "03_scanned_2pages.pdf")
        val fd = ParcelFileDescriptor.open(scanned, ParcelFileDescriptor.MODE_READ_ONLY)
        val bmp: Bitmap = PdfRenderer(fd).use { r ->
            r.openPage(0).use { p ->
                Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        fd.close()
        val name = "propdf_test_searchable_${System.nanoTime()}.pdf"
        val result = SearchablePdfGenerator(ctx).generateSearchablePdf(listOf(bmp), name)
        val uri = result.getOrThrow()
        try {
            val copy = File(dir, "searchable.pdf")
            ctx.contentResolver.openInputStream(uri)!!.use { src -> copy.outputStream().use { src.copyTo(it) } }
            val text = extract(copy)
            assertTrue(
                "text layer missing or OCR failed; extracted='${text.take(80)}'",
                text.contains("DELTA-5521") || text.contains("SCANNED PAGE")
            )
        } finally {
            if (uri.scheme == "content") ctx.contentResolver.delete(uri, null, null) else File(uri.path!!).delete()
            bmp.recycle()
        }
    }
}
