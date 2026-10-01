package com.propdfeditor.batch.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.core.data.repository.PdfBoxCompressionRepository
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.QualityPreset
import com.propdf.core.saf.SafHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import java.util.Random

/**
 * Signed-PDF policy for every rewriting batch operation, plus compression correctness.
 * Android instrumented tests; NOT RUN in the authoring environment (no Gradle/SDK/device).
 */
@RunWith(AndroidJUnit4::class)
class PdfProcessorSignedAndCompressionTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var processor: PdfProcessor

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "signedcomp").apply { deleteRecursively(); mkdirs() }
        val repo = PdfBoxCompressionRepository(context, dispatchers, SafHelper(context), mockk(relaxed = true))
        processor = PdfProcessor(repo)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun uri(f: File): Uri = Uri.fromFile(f)

    private fun textPdf(name: String, vararg texts: String): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (t in texts) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(60f, 700f); cs.showText(t); cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    /** 3 pages: each has text plus a noisy 1200x1200 JPEG so recompression has real work to do. */
    private fun mixedPdf(name: String): File {
        val file = File(dir, name)
        val rnd = Random(42)
        PDDocument().use { doc ->
            for (i in 1..3) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val bmp = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
                val px = IntArray(1200 * 1200) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), 40 * i) }
                bmp.setPixels(px, 0, 1200, 0, 0, 1200, 1200)
                val image: PDImageXObject = JPEGFactory.createFromImage(doc, bmp, 0.95f)
                bmp.recycle()
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 50f, 300f, 400f, 400f)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(60f, 760f); cs.showText("Compression page $i"); cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun signedPdf(name: String): File {
        val plain = textPdf("plain_$name", "sign me")
        val tmp = File(dir, "tmp_$name")
        PDDocument.load(plain).use { doc ->
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

    private fun certifiedPdf(name: String): File {
        val file = File(dir, name)
        PDDocument.load(textPdf("plain_$name", "certified")).use { doc ->
            val perms = COSDictionary().apply { setItem(COSName.getPDFName("DocMDP"), COSDictionary()) }
            doc.documentCatalog.cosObject.setItem(COSName.PERMS, perms)
            doc.save(file)
        }
        return file
    }

    private fun assertRefused(out: File, block: () -> Unit) {
        try {
            block()
            fail("expected SignedPdfException")
        } catch (expected: SignedPdfException) {
            assertFalse("no output may be produced", out.exists() && out.length() > 0)
        }
    }

    // ------------------------------------------------------------------ signed PDFs

    @Test
    fun signedPdf_isRefusedByEveryRewritingOperation() {
        val signed = signedPdf("signed.pdf")
        val ocrPage = OcrPage(100, 100, listOf(OcrLine("x", 1, 1, 50, 20)))
        val ops: Map<String, (File) -> Unit> = mapOf(
            "rotate" to { out -> runBlocking { processor.rotatePdf(context, uri(signed), uri(out), 90, null) } },
            "watermark" to { out ->
                runBlocking {
                    processor.watermarkPdf(context, uri(signed), uri(out),
                        WatermarkParams("X", 20f, 1f, Color.BLACK, 0f, "CENTER", null))
                }
            },
            "encrypt" to { out ->
                runBlocking {
                    processor.encryptPdf(context, uri(signed), uri(out),
                        EncryptParams("pw", true, true, false, "AES_256"))
                }
            },
            "decrypt" to { out -> runBlocking { processor.decryptPdf(context, uri(signed), uri(out), "") } },
            "compress" to { out ->
                runBlocking { processor.compressPdf(context, uri(signed), uri(out), QualityPreset.EBOOK.config) }
                Unit
            },
            "ocr" to { out ->
                runBlocking { processor.writeSearchablePdf(context, signed, uri(out), listOf(ocrPage)) }
            }
        )
        for ((name, op) in ops) {
            val out = File(dir, "out_$name.pdf")
            assertRefused(out) { op(out) }
        }
    }

    @Test
    fun certifiedPdf_docMdp_isRefusedToo() {
        val certified = certifiedPdf("certified.pdf")
        val out = File(dir, "out_cert.pdf")
        assertRefused(out) { runBlocking { processor.rotatePdf(context, uri(certified), uri(out), 90, null) } }
    }

    @Test
    fun unsignedPdf_isStillProcessed() = runBlocking {
        val plain = textPdf("p.pdf", "hello")
        val out = File(dir, "rot.pdf")
        processor.rotatePdf(context, uri(plain), uri(out), 90, null)
        PDDocument.load(out).use { assertEquals(90, it.getPage(0).rotation) }
    }

    // ------------------------------------------------------------------ compression

    private fun runCompression(optimizeImages: Boolean): Triple<File, File, com.propdf.core.domain.model.CompressionResult> {
        val src = mixedPdf("mixed_$optimizeImages.pdf")
        val out = File(dir, "compressed_$optimizeImages.pdf")
        val result = runBlocking {
            processor.compressPdf(context, uri(src), uri(out),
                QualityPreset.EBOOK.config.copy(optimizeImages = optimizeImages))
        }
        return Triple(src, out, result)
    }

    private fun assertCompressedDocumentIsSound(out: File) {
        assertTrue("output must be non-empty", out.length() > 0)
        PDDocument.load(out).use { doc ->
            assertEquals("page count unchanged", 3, doc.numberOfPages)
            val text = PDFTextStripper().getText(doc)
            for (i in 1..3) assertTrue("text of page $i kept", text.contains("Compression page $i"))
            for (i in 0 until 3) {
                val images = doc.getPage(i).resources.xObjectNames
                    .map { doc.getPage(i).resources.getXObject(it) }
                    .filterIsInstance<PDImageXObject>()
                assertTrue("page $i keeps its image resource", images.isNotEmpty())
                val bmp = PDFRenderer(doc).renderImageWithDPI(i, 36f)
                var nonWhite = 0
                for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) {
                    if (bmp.getPixel(x, y) != Color.WHITE) nonWhite++
                }
                bmp.recycle()
                assertTrue("page $i is not blank", nonWhite > 50)
            }
        }
    }

    @Test
    fun compression_optimizeImagesTrue_keepsPagesTextAndImages_lossyDocumented() {
        val (src, out, result) = runCompression(optimizeImages = true)
        assertCompressedDocumentIsSound(out)
        assertTrue("images were processed (lossy re-encode / downsample)", result.imagesProcessed >= 1)
        // Not asserted strictly: tiny fixtures may legitimately grow. Recorded for the test log only.
        println("compression size ${src.length()} -> ${out.length()}")
    }

    @Test
    fun compression_optimizeImagesFalse_doesNotTouchImages() {
        val (_, out, result) = runCompression(optimizeImages = false)
        assertCompressedDocumentIsSound(out)
        assertEquals("no image re-encoding when optimizeImages=false", 0, result.imagesProcessed)
    }
}
