package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.core.data.repository.PdfBoxCompressionRepository
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.result.AppResult
import com.propdf.core.saf.SafHelper
import com.propdfeditor.batch.util.PdfProcessor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.Random

/**
 * Editor-screen compress / optimize now go through the PDFBox compression repository.
 * Checks result correctness, that the source file is never modified, signed/encrypted/corrupt input
 * handling, rejected options, and that failed runs leave no output files in the cache.
 * Android instrumented test; NOT RUN in the authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class EditorCompressInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var repo: PdfOperationsRepositoryImpl

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
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
        dir = File(context.cacheDir, "editorcompress").apply { deleteRecursively(); mkdirs() }
        val compression = PdfBoxCompressionRepository(context, dispatchers, SafHelper(context), mockk(relaxed = true))
        repo = PdfOperationsRepositoryImpl(context, dispatchers, NoopLogger(), PdfProcessor(compression))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        outputsInCache().forEach { it.delete() }
    }

    private fun outputsInCache(): List<File> =
        context.cacheDir.listFiles { f -> f.name.startsWith("compressed_") || f.name.startsWith("optimized_") }
            ?.toList().orEmpty()

    private fun mixedPdf(name: String): File {
        val file = File(dir, name)
        val rnd = Random(7)
        PDDocument().use { doc ->
            for (i in 1..3) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                bmp.setPixels(IntArray(1000 * 1000) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), 30 * i) }, 0, 1000, 0, 0, 1000, 1000)
                val image = JPEGFactory.createFromImage(doc, bmp, 0.95f)
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

    private fun textPdf(name: String): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(60f, 700f); cs.showText("sign me"); cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    /** Test fixture only: structurally signed (dummy CMS bytes, no key material). */
    private fun signedPdf(name: String): File {
        val plain = textPdf("plain_$name")
        val out = File(dir, name)
        PDDocument.load(plain).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only")
                setSignDate(Calendar.getInstance())
            }
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            out.outputStream().use { doc.saveIncremental(it) }
        }
        return out
    }

    private fun encryptedPdf(name: String): File {
        val plain = textPdf("plain_$name")
        val out = File(dir, name)
        PDDocument.load(plain).use { doc ->
            val policy = StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
        return out
    }

    private fun pagesOf(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    private fun fileOf(uri: Uri): File = File(uri.path!!)

    @Test
    fun compressPdf_success_preservesPagesAndText_andNeverTouchesTheSource() = runBlocking<Unit> {
        val src = mixedPdf("src.pdf")
        val before = src.readBytes()
        val result = repo.compressPdf(Uri.fromFile(src), CompressConfig(level = 7, imageQuality = 40))
        assertTrue("expected success but was $result", result is AppResult.Success)
        val out = fileOf((result as AppResult.Success).data)
        assertTrue(out.exists() && out.length() > 0)
        assertEquals(3, pagesOf(out))
        PDDocument.load(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("Compression page 1"))
            assertTrue(text.contains("Compression page 3"))
        }
        assertArrayEquals("source must not be modified", before, src.readBytes())
        out.delete()
    }

    @Test
    fun optimizePdf_success_producesReadableDocumentWithSamePages() = runBlocking<Unit> {
        val src = mixedPdf("opt.pdf")
        for (aggressive in listOf(false, true)) {
            val result = repo.optimizePdf(Uri.fromFile(src), aggressive)
            assertTrue("aggressive=$aggressive: $result", result is AppResult.Success)
            val out = fileOf((result as AppResult.Success).data)
            assertEquals(3, pagesOf(out))
            out.delete()
        }
    }

    @Test
    fun compressPdf_signedPdf_isRefused_noOutputLeftBehind_sourceUntouched() = runBlocking<Unit> {
        val src = signedPdf("signed.pdf")
        val before = src.readBytes()
        val result = repo.compressPdf(Uri.fromFile(src), CompressConfig())
        assertTrue("signed input must be refused but was $result", result is AppResult.Error)
        val message = (result as AppResult.Error).message.orEmpty()
        assertTrue("message should explain the refusal: '$message'", message.contains("sign", ignoreCase = true))
        assertTrue(outputsInCache().isEmpty())
        assertArrayEquals(before, src.readBytes())
    }

    @Test
    fun compressPdf_corruptInput_fails_noOutputLeftBehind_sourceUntouched() = runBlocking<Unit> {
        val src = File(dir, "corrupt.pdf").apply { writeText("%PDF-1.7 this is not a real pdf") }
        val before = src.readBytes()
        val result = repo.compressPdf(Uri.fromFile(src), CompressConfig())
        assertTrue("corrupt input must fail but was $result", result is AppResult.Error)
        assertTrue(outputsInCache().isEmpty())
        assertArrayEquals(before, src.readBytes())
    }

    @Test
    fun compressPdf_encryptedInput_failsSafely_sourceUntouched() = runBlocking<Unit> {
        val src = encryptedPdf("encrypted.pdf")
        val before = src.readBytes()
        val result = repo.compressPdf(Uri.fromFile(src), CompressConfig())
        assertTrue("encrypted input without a password must fail but was $result", result is AppResult.Error)
        assertTrue(outputsInCache().isEmpty())
        assertArrayEquals(before, src.readBytes())
    }

    @Test
    fun compressPdf_missingSource_failsWithoutOutput() = runBlocking<Unit> {
        val result = repo.compressPdf(Uri.fromFile(File(dir, "does-not-exist.pdf")), CompressConfig())
        assertTrue(result is AppResult.Error)
        assertTrue(outputsInCache().isEmpty())
    }

    @Test
    fun compressPdf_flattenFormsOption_isRejectedNotIgnored() = runBlocking<Unit> {
        val src = mixedPdf("flatten.pdf")
        val result = repo.compressPdf(Uri.fromFile(src), CompressConfig(flattenForms = true))
        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.orEmpty().contains("flatten", ignoreCase = true))
        assertTrue(outputsInCache().isEmpty())
    }
}
