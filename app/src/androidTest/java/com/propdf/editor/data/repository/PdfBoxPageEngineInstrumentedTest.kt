package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.BackgroundConfig
import com.propdf.core.domain.model.CropConfig
import com.propdf.core.domain.model.HeaderFooterConfig
import com.propdf.core.domain.model.ImageFitMode
import com.propdf.core.domain.model.ImageInsertionConfig
import com.propdf.core.domain.model.MergeConfig
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.ResizeConfig
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.core.domain.model.WatermarkPosition
import com.propdf.core.domain.result.AppResult
import com.propdfeditor.batch.util.PdfPasswordException
import com.propdfeditor.batch.util.SignedPdfException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Phase 2 regression suite for the Page Editor PDFBox engine.
 *
 * Every page of every fixture carries a unique text label ("A1", "B2", ...), so page ORDER and
 * "each page occurs exactly once" are asserted from extracted text, not from page counts alone.
 * Sources are served through the existing test content:// provider (TestPdfProvider) as well as
 * file:// so both SAF schemes are exercised.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxPageEngineInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repo: PdfOperationsRepositoryImpl
    private lateinit var dir: File        // served by TestPdfProvider as content://com.propdfeditor.test.pdfs/<name>

    private class TestDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val unconfined = Dispatchers.Unconfined
    }

    private class NoopLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "testpdfs").apply { deleteRecursively(); mkdirs() }
        repo = PdfOperationsRepositoryImpl(context, TestDispatchers(), NoopLogger())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    private data class Spec(
        val label: String,
        val width: Float = 200f,
        val height: Float = 300f,
        val rotation: Int = 0,
        val originX: Float = 0f,
        val originY: Float = 0f,
        val crop: PDRectangle? = null,
        val link: Boolean = false
    )

    private fun build(name: String, specs: List<Spec>, configure: (PDDocument) -> Unit = {}): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (s in specs) {
                val page = PDPage(PDRectangle(s.originX, s.originY, s.width, s.height))
                page.rotation = s.rotation
                s.crop?.let { page.cropBox = it }
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(s.originX + 20f, s.originY + 40f)
                    cs.showText(s.label); cs.endText()
                }
                if (s.link) page.annotations = listOf(PDAnnotationLink().apply {
                    rectangle = PDRectangle(s.originX + 10f, s.originY + 10f, 50f, 20f)
                })
            }
            configure(doc)
            doc.save(file)
        }
        return file
    }

    private fun labeled(prefix: String, n: Int, rotation: Int = 0) =
        (1..n).map { Spec("$prefix$it", rotation = rotation) }

    private fun contentUri(f: File): Uri = Uri.parse("content://com.propdfeditor.test.pdfs/${f.name}")

    private fun uriOf(r: AppResult<Uri>): Uri = when (r) {
        is AppResult.Success -> r.data
        else -> { fail("expected success but was $r"); throw IllegalStateException() }
    }

    private fun fileOf(r: AppResult<Uri>): File = File(requireNotNull(uriOf(r).path))

    private fun labels(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { n ->
            PDFTextStripper().also { it.startPage = n; it.endPage = n }.getText(doc).trim().split(Regex("\\s+")).first()
        }
    }

    private fun pageCount(file: File) = PDDocument.load(file).use { it.numberOfPages }

    /** Opens every page with Android's PdfRenderer (the viewer's engine) and renders it. */
    private fun assertRenderable(file: File, expectedPages: Int) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                assertEquals(expectedPages, r.pageCount)
                for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                    val bmp = Bitmap.createBitmap(p.width.coerceAtLeast(1), p.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.recycle()
                }
            }
        } finally { pfd.close() }
    }

    private fun assertExactlyOnce(expected: List<String>, actual: List<String>) {
        assertEquals(expected, actual)
        assertEquals("no page may appear twice", actual.size, actual.toSet().size)
    }

    // ------------------------------------------------------------------ merge / split

    @Test fun merge_keepsEveryPageExactlyOnce_inOrder_viaContentAndFileUris() = runBlocking<Unit> {
        val a = build("a.pdf", labeled("A", 3))
        val b = build("b.pdf", labeled("B", 2, rotation = 90))
        val r = repo.mergePdfs(MergeConfig(listOf(contentUri(a), Uri.fromFile(b)), "merged"))
        val out = fileOf(r)
        assertExactlyOnce(listOf("A1", "A2", "A3", "B1", "B2"), labels(out))
        assertRenderable(out, 5)
        PDDocument.load(out).use { assertEquals(90, it.getPage(3).rotation) }
        // the cache copy made for the content:// source must be gone
        assertTrue(context.cacheDir.listFiles { f -> f.name.startsWith("pdf_op_src_") }.isNullOrEmpty())
    }

    @Test fun split_partitionsDocumentExactlyOnce() = runBlocking<Unit> {
        val src = build("s.pdf", labeled("S", 7))
        val r = repo.splitEveryNPages(contentUri(src), 3, "part")
        val parts = (r as AppResult.Success).data.map { File(it.path!!) }
        assertEquals(listOf(3, 3, 1), parts.map { pageCount(it) })
        assertExactlyOnce((1..7).map { "S$it" }, parts.flatMap { labels(it) })
        parts.forEach { assertRenderable(it, pageCount(it)) }
    }

    @Test fun splitRanges_fileBased_clampsAndNamesParts() = runBlocking<Unit> {
        val src = build("r.pdf", labeled("R", 5))
        val outDir = File(context.cacheDir, "splitout").apply { deleteRecursively(); mkdirs() }
        val r = repo.split(com.propdf.core.domain.model.SplitRequest(src.absolutePath, listOf(1..2, 3..99), outDir.absolutePath))
        val parts = (r as AppResult.Success).data
        assertExactlyOnce((1..5).map { "R$it" }, parts.flatMap { labels(it) })
        outDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ reorder / duplicate / delete / extract

    @Test fun move_isAPermutation_andKeepsFormsAndAnnotations() = runBlocking<Unit> {
        val src = build("m.pdf", (1..4).map { Spec("M$it", link = it == 2) }) { doc ->
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "name"
            form.fields.add(field)
        }
        val out = fileOf(repo.movePages(contentUri(src), listOf(4), 0))
        assertExactlyOnce(listOf("M4", "M1", "M2", "M3"), labels(out))
        PDDocument.load(out).use { d ->
            assertNotNull("AcroForm must survive an in-place reorder", d.documentCatalog.acroForm)
            assertEquals(1, d.getPage(2).annotations.size) // the link moved with M2
        }
        assertRenderable(out, 4)
    }

    @Test fun duplicate_addsExactlyOneCopyPerSelectedPage_regressionForDoubleAdd() = runBlocking<Unit> {
        val src = build("d.pdf", labeled("D", 4))
        val out = fileOf(repo.duplicatePages(Uri.fromFile(src), listOf(1, 3)))
        assertEquals(6, pageCount(out))
        assertEquals(listOf("D1", "D1", "D2", "D3", "D3", "D4"), labels(out))
        assertRenderable(out, 6)
        // copies are independent page objects: rotating one must not rotate its twin
        val rotated = fileOf(repo.rotatePages(Uri.fromFile(out), listOf(2), 90))
        PDDocument.load(rotated).use {
            assertEquals(0, it.getPage(0).rotation); assertEquals(90, it.getPage(1).rotation)
        }
    }

    @Test fun delete_removesPagesAndTheirContent() = runBlocking<Unit> {
        val src = build("x.pdf", listOf(Spec("KEEP1"), Spec("SECRET2"), Spec("KEEP3")))
        val out = fileOf(repo.deletePages(contentUri(src), listOf(2)))
        assertEquals(listOf("KEEP1", "KEEP3"), labels(out))
        assertFalse("deleted page text must not linger in the file", out.readBytes().toString(Charsets.ISO_8859_1).contains("SECRET2"))
        PDDocument.load(out).use { d ->
            assertFalse(PDFTextStripper().getText(d).contains("SECRET2"))
        }
        assertRenderable(out, 2)
    }

    @Test fun delete_everyPage_isRefusedWithoutOutput() = runBlocking<Unit> {
        val src = build("all.pdf", labeled("Z", 2))
        val before = context.cacheDir.listFiles { f -> f.name.startsWith("deleted_") }?.size ?: 0
        assertTrue(repo.deletePages(Uri.fromFile(src), listOf(1, 2)) is AppResult.Error)
        assertEquals(before, context.cacheDir.listFiles { f -> f.name.startsWith("deleted_") }?.size ?: 0)
    }

    @Test fun extract_keepsSelectionOrder_andOnlyThosePages() = runBlocking<Unit> {
        val src = build("e.pdf", labeled("E", 5))
        val out = fileOf(repo.extractPages(contentUri(src), listOf(4, 2), "ext"))
        assertExactlyOnce(listOf("E4", "E2"), labels(out))
        assertRenderable(out, 2)
    }

    @Test fun reorder_viaExtractPermutation_isLossless() = runBlocking<Unit> {
        val src = build("o.pdf", labeled("O", 4))
        val out = fileOf(repo.extractPages(Uri.fromFile(src), listOf(3, 1, 4, 2), "reordered"))
        assertExactlyOnce(listOf("O3", "O1", "O4", "O2"), labels(out))
    }

    // ------------------------------------------------------------------ insert

    @Test fun insertPdf_placesPagesAtPosition_everyPageOnce() = runBlocking<Unit> {
        val main = build("main.pdf", labeled("M", 3))
        val ins = build("ins.pdf", labeled("I", 2))
        val out = fileOf(repo.insertPdfPages(contentUri(main), contentUri(ins), 2, emptyList()))
        assertExactlyOnce(listOf("M1", "I1", "I2", "M2", "M3"), labels(out))
        assertRenderable(out, 5)
    }

    @Test fun insertBlank_hasRequestedSize() = runBlocking<Unit> {
        val src = build("b.pdf", labeled("B", 2))
        val out = fileOf(repo.insertBlankPage(Uri.fromFile(src), 2, 300f, 400f))
        assertEquals(3, pageCount(out))
        PDDocument.load(out).use {
            assertEquals(300f, it.getPage(1).mediaBox.width, 0.01f)
            assertEquals(400f, it.getPage(1).mediaBox.height, 0.01f)
            assertEquals(200f, it.getPage(2).mediaBox.width, 0.01f)
        }
        assertRenderable(out, 3)
    }

    // ------------------------------------------------------------------ rotate / crop / resize

    @Test fun rotate_wrapsAround_andNeverGoesNegative() = runBlocking<Unit> {
        val src = build("rot.pdf", listOf(Spec("R1", rotation = 270), Spec("R2")))
        val out = fileOf(repo.rotatePages(Uri.fromFile(src), listOf(1, 2), -90))
        PDDocument.load(out).use {
            assertEquals(180, it.getPage(0).rotation); assertEquals(270, it.getPage(1).rotation)
        }
        val back = fileOf(repo.rotatePages(Uri.fromFile(out), listOf(1), 180))
        PDDocument.load(back).use { assertEquals(0, it.getPage(0).rotation) }
        assertRenderable(back, 2)
    }

    @Test fun crop_marginsFollowTheDisplayedPage_onRotatedAndOffsetBoxes() = runBlocking<Unit> {
        val specs = listOf(
            Spec("C0", rotation = 0, originX = 10f, originY = 20f),
            Spec("C90", rotation = 90, originX = 10f, originY = 20f)
        )
        val src = build("crop.pdf", specs)
        // display margins: left 10, top 20, right 30, bottom 40
        val cfg = CropConfig(leftMargin = 10f, rightMargin = 30f, topMargin = 20f, bottomMargin = 40f)
        val out = fileOf(repo.cropPages(Uri.fromFile(src), emptyList(), cfg))
        PDDocument.load(out).use { d ->
            val c0 = d.getPage(0).cropBox
            assertEquals(10f + 10f, c0.lowerLeftX, 0.01f); assertEquals(20f + 40f, c0.lowerLeftY, 0.01f)
            assertEquals(210f - 30f, c0.upperRightX, 0.01f); assertEquals(320f - 20f, c0.upperRightY, 0.01f)
            // rot 90: user left=top(20), bottom=left(10), right=bottom(40), top=right(30)
            val c90 = d.getPage(1).cropBox
            assertEquals(10f + 20f, c90.lowerLeftX, 0.01f); assertEquals(20f + 10f, c90.lowerLeftY, 0.01f)
            assertEquals(210f - 40f, c90.upperRightX, 0.01f); assertEquals(320f - 30f, c90.upperRightY, 0.01f)
            assertEquals(90, d.getPage(1).rotation)
        }
        assertRenderable(out, 2)
    }

    @Test fun resize_producesTargetDisplaySize_forPortraitLandscapeAndRotated() = runBlocking<Unit> {
        val specs = listOf(
            Spec("P", 200f, 300f), Spec("L", 300f, 200f), Spec("R", 200f, 300f, rotation = 90),
            Spec("O", 200f, 300f, originX = 15f, originY = 25f), Spec("A", link = true)
        )
        val src = build("rs.pdf", specs)
        val out = fileOf(repo.resizePages(Uri.fromFile(src), emptyList(), ResizeConfig(400f, 400f, keepAspectRatio = true)))
        assertEquals(listOf("P", "L", "R", "O", "A"), labels(out))
        PDDocument.load(out).use { d ->
            for (i in 0 until d.numberOfPages) {
                val p = d.getPage(i)
                val quarter = p.rotation == 90 || p.rotation == 270
                val dispW = if (quarter) p.cropBox.height else p.cropBox.width
                val dispH = if (quarter) p.cropBox.width else p.cropBox.height
                assertEquals("page $i displayed width", 400f, dispW, 0.01f)
                assertEquals("page $i displayed height", 400f, dispH, 0.01f)
            }
        }
        assertRenderable(out, 5)
        // resize must not drop content
        assertTrue(PDDocument.load(out).use { PDFTextStripper().getText(it).contains("P") })
    }

    @Test fun mirror_keepsPagesAndStaysRenderable() = runBlocking<Unit> {
        val src = build("mir.pdf", listOf(Spec("M1"), Spec("M2", rotation = 90)))
        val h = fileOf(repo.mirrorPages(Uri.fromFile(src), emptyList(), true))
        val v = fileOf(repo.mirrorPages(Uri.fromFile(src), listOf(2), false))
        assertEquals(listOf("M1", "M2"), labels(h)); assertEquals(listOf("M1", "M2"), labels(v))
        assertRenderable(h, 2); assertRenderable(v, 2)
    }

    // ------------------------------------------------------------------ watermark / numbers / header / background

    /** Centroid of non-white pixels as fractions of the rendered page, or null if the page is blank. */
    private fun inkCentroid(file: File, pageIndex: Int): Pair<Float, Float>? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                r.openPage(pageIndex).use { p ->
                    val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var sx = 0.0; var sy = 0.0; var n = 0
                    for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
                        val c = bmp.getPixel(x, y)
                        if (Color.red(c) < 235 || Color.green(c) < 235 || Color.blue(c) < 235) { sx += x; sy += y; n++ }
                    }
                    val w = bmp.width.toFloat(); val h = bmp.height.toFloat(); bmp.recycle()
                    return if (n == 0) null else Pair((sx / n / w).toFloat(), (sy / n / h).toFloat())
                }
            }
        } finally { pfd.close() }
    }

    @Test fun watermark_isCentredOnTheVisiblePage_forEveryRotationCropAndOrigin() = runBlocking<Unit> {
        // Blank pages (no label text) so the only ink is the watermark.
        val specs = listOf(
            Spec("-", rotation = 0), Spec("-", rotation = 90), Spec("-", rotation = 180), Spec("-", rotation = 270),
            Spec("-", 300f, 200f, rotation = 90),                                   // landscape + rotated
            Spec("-", originX = 40f, originY = 60f),                                // MediaBox not at origin
            Spec("-", crop = PDRectangle(20f, 30f, 120f, 200f))                     // CropBox smaller than MediaBox
        ).map { it.copy(label = "") }
        val src = build("wm.pdf", specs)
        val cfg = WatermarkConfig(text = "WM", opacity = 1f, rotation = 0f, fontSize = 60f)
        val out = fileOf(repo.addWatermark(Uri.fromFile(src), cfg))
        assertEquals(specs.size, pageCount(out))
        for (i in specs.indices) {
            val c = inkCentroid(out, i)
            assertNotNull("page $i has no watermark ink", c)
            assertEquals("page $i horizontal centre", 0.5f, c!!.first, 0.12f)
            assertEquals("page $i vertical centre", 0.5f, c.second, 0.12f)
        }
    }

    @Test fun watermark_tileAndCornerPositionsRender() = runBlocking<Unit> {
        val src = build("wm2.pdf", listOf(Spec(""), Spec("", rotation = 90)))
        for (pos in listOf(WatermarkPosition.TILE, WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_RIGHT)) {
            val out = fileOf(repo.addWatermark(Uri.fromFile(src), WatermarkConfig(text = "DRAFT", opacity = 1f, fontSize = 20f, position = pos)))
            assertRenderable(out, 2)
            assertNotNull(inkCentroid(out, 0))
        }
    }

    @Test fun pageNumbers_headerFooter_background_renderOnRotatedPages() = runBlocking<Unit> {
        val src = build("deco.pdf", listOf(Spec("D1"), Spec("D2", rotation = 90), Spec("D3", rotation = 270)))
        val numbered = fileOf(repo.addPageNumbers(Uri.fromFile(src), PageNumberConfig()))
        assertRenderable(numbered, 3)
        PDDocument.load(numbered).use { d ->
            assertTrue(PDFTextStripper().getText(d).contains("Page 1 of 3"))
            assertTrue(PDFTextStripper().getText(d).contains("Page 3 of 3"))
        }
        val hf = fileOf(repo.addHeaderFooter(Uri.fromFile(src), HeaderFooterConfig(headerText = "Head", footerText = "Foot")))
        assertRenderable(hf, 3)
        val bg = fileOf(repo.addBackground(Uri.fromFile(src), BackgroundConfig(color = 0xFFFFFF00.toInt(), opacity = 1f)))
        assertRenderable(bg, 3)
        assertEquals(listOf("D1", "D2", "D3"), labels(bg))
    }

    // ------------------------------------------------------------------ images

    private fun pngFile(name: String, w: Int, h: Int, transparent: Boolean): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(if (transparent) Color.TRANSPARENT else Color.RED)
        if (transparent) for (x in 0 until w / 2) for (y in 0 until h) bmp.setPixel(x, y, Color.argb(255, 0, 0, 255))
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    @Test fun imagePage_scalesToPageAndKeepsAspect_viaContentUri() = runBlocking<Unit> {
        val src = build("img.pdf", labeled("I", 2))
        val image = pngFile("wide.png", 1000, 500, transparent = false)
        val cfg = ImageInsertionConfig(contentUri(image), 595f, 842f, ImageFitMode.FIT_CENTER, 36f)
        val out = fileOf(repo.insertImagePage(Uri.fromFile(src), 2, cfg))
        assertEquals(3, pageCount(out))
        PDDocument.load(out).use {
            assertEquals(595f, it.getPage(1).mediaBox.width, 0.01f)
            assertEquals(842f, it.getPage(1).mediaBox.height, 0.01f)
        }
        assertRenderable(out, 3)
        val c = inkCentroid(out, 1)!!
        assertEquals(0.5f, c.first, 0.05f); assertEquals(0.5f, c.second, 0.05f)
    }

    @Test fun combineImages_oneValidPagePerImage_includingTransparentPng() = runBlocking<Unit> {
        val a = pngFile("a.png", 400, 300, false)
        val b = pngFile("b.png", 300, 400, true)
        val cfg = ImageInsertionConfig(Uri.fromFile(a), 595f, 842f, ImageFitMode.FIT_WIDTH, 20f)
        val out = fileOf(repo.combineImagesToPdf(listOf(contentUri(a), Uri.fromFile(b)), "combo", cfg))
        assertEquals(2, pageCount(out))
        assertRenderable(out, 2)
    }

    @Test fun insertImageOnLastPage_drawsUprightOnRotatedPage() = runBlocking<Unit> {
        val src = build("last.pdf", listOf(Spec(""), Spec("", rotation = 90)))
        val image = pngFile("sq.png", 100, 100, false)
        val out = File(context.cacheDir, "onpage_${System.nanoTime()}.pdf")
        val r = repo.insertImageOnPage(src, out, ImageInsertionConfig(Uri.fromFile(image), margin = 60f))
        assertTrue(r is AppResult.Success)
        assertRenderable(out, 2)
        val c = inkCentroid(out, 1)!!
        assertEquals(0.5f, c.first, 0.06f); assertEquals(0.5f, c.second, 0.06f)
        out.delete()
    }

    // ------------------------------------------------------------------ safety / edge cases

    @Test fun encryptedInput_isRefused_notSilentlyDecrypted() = runBlocking<Unit> {
        val file = File(dir, "enc.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(file)
        }
        val r = repo.deletePages(Uri.fromFile(file), listOf(1))
        assertTrue(r is AppResult.Error)
        assertTrue(repo.rotatePages(Uri.fromFile(file), listOf(1), 90) is AppResult.Error)
        // direct engine call: typed exception, and no output published
        val out = File(context.cacheDir, "enc_out_${System.nanoTime()}.pdf")
        try {
            PdfBoxPageEngine(context).rotate(file, out, mapOf(1 to 90)); fail("expected PdfPasswordException")
        } catch (expected: PdfPasswordException) { }
        assertFalse(out.exists())
        assertTrue(out.parentFile!!.listFiles { f -> f.name.endsWith(".tmp") && f.name.contains(out.name) }.isNullOrEmpty())
    }

    @Test fun signedInput_isRefused_notCorrupted() = runBlocking<Unit> {
        val plain = build("plain.pdf", labeled("S", 1))
        val signed = File(dir, "signed.pdf")
        val tmp = File(dir, "signed.tmp")
        PDDocument.load(plain).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only"); setSignDate(Calendar.getInstance())
            }
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            FileOutputStream(tmp).use { doc.saveIncremental(it) }
        }
        tmp.copyTo(signed, overwrite = true)
        val before = signed.readBytes()
        val out = File(context.cacheDir, "signed_out_${System.nanoTime()}.pdf")
        for (op in listOf<suspend () -> Unit>(
            { PdfBoxPageEngine(context).rotate(signed, out, mapOf(1 to 90)) },
            { PdfBoxPageEngine(context).watermark(signed, out, WatermarkConfig()) },
            { PdfBoxPageEngine(context).assemble(signed, out, listOf(1, 1)) }
        )) {
            try { op(); fail("expected SignedPdfException") } catch (expected: SignedPdfException) { }
            assertFalse(out.exists())
        }
        assertTrue("original must be untouched", before.contentEquals(signed.readBytes()))
    }

    @Test fun malformedAndEmptyInputs_failCleanly_withoutOutput() = runBlocking<Unit> {
        val garbage = File(dir, "garbage.pdf").apply { writeText("this is not a pdf") }
        val empty = File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        for (f in listOf(garbage, empty)) {
            assertTrue(repo.getPageCount(Uri.fromFile(f)) is AppResult.Error)
            assertTrue(repo.rotatePages(contentUri(f), listOf(1), 90) is AppResult.Error)
            assertTrue(repo.mergePdfs(MergeConfig(listOf(Uri.fromFile(f)), "bad")) is AppResult.Error)
        }
        assertTrue(context.cacheDir.listFiles { f -> f.name.startsWith("pdf_op_src_") }.isNullOrEmpty())
        assertTrue(context.cacheDir.listFiles { f -> f.name.endsWith(".tmp") && f.name.startsWith(".") }.isNullOrEmpty())
    }

    @Test fun missingSource_reportsError() = runBlocking<Unit> {
        val gone = Uri.fromFile(File(dir, "nope.pdf"))
        assertTrue(repo.getPageCount(gone) is AppResult.Error)
        assertTrue(repo.getPageCount(Uri.parse("content://com.propdfeditor.test.pdfs/nope.pdf")) is AppResult.Error)
    }

    @Test fun existingAnnotationsAndInheritedAttributes_surviveReorder() = runBlocking<Unit> {
        val file = File(dir, "inherit.pdf")
        PDDocument().use { doc ->
            // MediaBox and Rotate declared ONCE on the page-tree root and inherited by the pages.
            val a = PDPage(); val b = PDPage()
            doc.addPage(a); doc.addPage(b)
            for ((p, label) in listOf(a to "H1", b to "H2")) {
                PDPageContentStream(doc, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(20f, 40f)
                    cs.showText(label); cs.endText()
                }
            }
            val root = doc.documentCatalog.pages.cosObject
            root.setItem(COSName.MEDIA_BOX, PDRectangle(0f, 0f, 250f, 350f).cosObject)
            root.setInt(COSName.ROTATE, 90)
            for (p in listOf(a, b)) { p.cosObject.removeItem(COSName.MEDIA_BOX); p.cosObject.removeItem(COSName.ROTATE) }
            doc.save(file)
        }
        val out = fileOf(repo.movePages(Uri.fromFile(file), listOf(2), 0))
        assertEquals(listOf("H2", "H1"), labels(out))
        PDDocument.load(out).use { d ->
            for (i in 0..1) {
                assertEquals(250f, d.getPage(i).mediaBox.width, 0.01f)
                assertEquals(90, d.getPage(i).rotation)
            }
        }
        assertRenderable(out, 2)
    }

    @Test fun input_isNeverModified() = runBlocking<Unit> {
        val src = build("orig.pdf", labeled("N", 3))
        val before = src.readBytes()
        repo.deletePages(Uri.fromFile(src), listOf(1)); repo.movePages(contentUri(src), listOf(3), 0)
        repo.rotatePages(Uri.fromFile(src), listOf(1), 90); repo.addWatermark(contentUri(src), WatermarkConfig())
        assertTrue(before.contentEquals(src.readBytes()))
    }

    @Test fun cancellation_leavesNoTempFilesAndNoOutput() {
        val src = build("big.pdf", labeled("C", 60))
        val out = File(context.cacheDir, "cancel_out_${System.nanoTime()}.pdf")
        val engine = PdfBoxPageEngine(context)
        val job = GlobalScope.launch(Dispatchers.IO) {
            engine.watermark(src, out, WatermarkConfig(position = WatermarkPosition.TILE, fontSize = 8f))
        }
        runBlocking { job.cancelAndJoin() }
        assertTrue(job.isCancelled || out.exists())      // either cancelled in time, or it had already completed
        if (job.isCancelled) assertFalse("cancelled work must not publish output", out.exists())
        assertTrue(context.cacheDir.listFiles { f -> f.name.startsWith(".") && f.name.endsWith(".tmp") }.isNullOrEmpty())
        out.delete()
    }
}
