package com.propdfeditor.pdfedit

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.corpus.CorpusAssets
import com.propdf.pdfedit.convert.ConvertException
import com.propdf.pdfedit.convert.ConvertInput
import com.propdf.pdfedit.convert.ConvertJob
import com.propdf.pdfedit.convert.ConvertMode
import com.propdf.pdfedit.convert.ConvertRunner
import com.propdf.pdfedit.convert.DocumentTreeSink
import com.propdf.pdfedit.convert.PageSelection
import com.propdf.pdfedit.convert.PdfToImagesOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The runner behind the Convert screen: input copying, writing into a chosen folder without overwriting, cleanup of the
 * private work folder, passwords, and cancellation. A plain folder wrapped by DocumentFile stands in for a SAF folder.
 * (A raw DocumentFile appends the extension of the MIME type to the name, so these tests read names from the result
 * instead of hard-coding them; real SAF providers keep a name whose extension already matches.)
 */
@RunWith(AndroidJUnit4::class)
class ConvertRunnerInstrumentedTest {

    private lateinit var work: File
    private lateinit var out: File
    private lateinit var root: DocumentFile
    private lateinit var runner: ConvertRunner
    private lateinit var src: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PDFBoxResourceLoader.init(instrumentation.targetContext)
        // :app depends on :pdfedit, so the bundled fonts are assets of the app APK and the app's cache folder is writable
        runner = ConvertRunner(instrumentation.targetContext)
        work = CorpusAssets.workDir("runner")
        out = File(work, "out").apply { deleteRecursively(); mkdirs() }
        root = DocumentFile.fromFile(out)
        src = File(work, "src").apply { mkdirs() }
    }

    private fun corpus(name: String): ConvertInput = ConvertInput(Uri.fromFile(CorpusAssets.copyTo(src, name)), name)

    private fun png(name: String, color: Int): ConvertInput {
        val f = File(src, name)
        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return ConvertInput(Uri.fromFile(f), name)
    }

    private fun run(job: ConvertJob) = runBlocking { runner.run(job) }

    private fun job(mode: ConvertMode, inputs: List<ConvertInput>, name: String = "result", password: String? = null, pdfToImages: PdfToImagesOptions = PdfToImagesOptions(dpi = 36)) =
        ConvertJob(mode, inputs, root, name, password, pdfToImages = pdfToImages)

    private fun leftoverWorkFolders(): List<String> =
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.list().orEmpty().filter { it.startsWith("convert-") }

    private fun expectReason(reason: ConvertException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected " + reason)
        } catch (e: ConvertException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun pdfToImagesWritesTheFilesIntoTheFolderAndCleansUp() {
        val result = run(job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("01_normal.pdf"))))
        assertEquals(3, result.outputNames.size)
        assertEquals(result.outputNames.sorted(), out.list().orEmpty().sorted())
        assertTrue(leftoverWorkFolders().isEmpty())
    }

    @Test
    fun aSecondRunNeverOverwritesTheFirst() {
        val first = run(job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("01_normal.pdf"))))
        val hashes = first.outputNames.associateWith { CorpusAssets.sha256(File(out, it)) }
        val second = run(job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("01_normal.pdf"))))
        assertEquals(6, out.list().orEmpty().size)
        assertTrue("second run names must be new: " + second.outputNames, second.outputNames.none { it in first.outputNames })
        assertTrue(second.outputNames.all { it.contains(" (1)") })
        for ((name, hash) in hashes) assertEquals("original changed: " + name, hash, CorpusAssets.sha256(File(out, name)))
    }

    @Test
    fun pdfToTextProducesAReadableTextFile() {
        val result = run(job(ConvertMode.PDF_TO_TEXT, listOf(corpus("01_normal.pdf"))))
        assertEquals(1, result.outputNames.size)
        assertTrue(File(out, result.outputNames[0]).readText().contains("ALPHA-7731"))
    }

    @Test
    fun imagesToPdfKeepsTheListOrderAndMakesOnePdf() {
        val result = run(job(ConvertMode.IMAGES_TO_PDF, listOf(png("a.png", Color.RED), png("b.png", Color.BLUE)), name = "images"))
        assertEquals(1, result.outputNames.size)
        PDDocument.load(File(out, result.outputNames[0])).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun textToPdfMakesAnExtractablePdf() {
        val text = File(src, "note.txt").apply { writeText("Hello from the Convert screen runner", Charsets.UTF_8) }
        val result = run(job(ConvertMode.TEXT_TO_PDF, listOf(ConvertInput(Uri.fromFile(text), "note.txt")), name = "note"))
        val extracted = PDDocument.load(File(out, result.outputNames[0])).use { PDFTextStripper().getText(it) }
        assertTrue(extracted.contains("Hello from the Convert screen runner"))
    }

    @Test
    fun anEncryptedPdfAsksForThePasswordAndWorksWithIt() {
        expectReason(ConvertException.Reason.PASSWORD_REQUIRED) {
            run(job(ConvertMode.PDF_TO_TEXT, listOf(corpus("04_encrypted_aes256.pdf"))))
        }
        assertTrue(out.list().orEmpty().isEmpty())
        assertTrue(leftoverWorkFolders().isEmpty())
        val result = run(job(ConvertMode.PDF_TO_TEXT, listOf(corpus("04_encrypted_aes256.pdf")), password = CorpusAssets.USER_PW))
        assertTrue(File(out, result.outputNames[0]).readText().contains("ALPHA-7731"))
    }

    @Test
    fun wrongInputCountsAreRefused() {
        expectReason(ConvertException.Reason.EMPTY_SELECTION) { run(job(ConvertMode.PDF_TO_TEXT, emptyList())) }
        expectReason(ConvertException.Reason.EMPTY_SELECTION) {
            run(job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("01_normal.pdf"), corpus("08_rotated_pages.pdf"))))
        }
        assertTrue(out.list().orEmpty().isEmpty())
    }

    @Test
    fun aFileThatCannotBeReadFailsCleanly() {
        val missing = ConvertInput(Uri.fromFile(File(src, "does-not-exist.pdf")), "does-not-exist.pdf")
        expectReason(ConvertException.Reason.UNREADABLE) { run(job(ConvertMode.PDF_TO_IMAGES, listOf(missing))) }
        assertTrue(out.list().orEmpty().isEmpty())
        assertTrue(leftoverWorkFolders().isEmpty())
    }

    @Test
    fun invalidPageListsAreRefusedAndWriteNothing() {
        expectReason(ConvertException.Reason.INVALID_RANGE) {
            run(job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("01_normal.pdf")), pdfToImages = PdfToImagesOptions(dpi = 36, pages = PageSelection.Ranges("1-99"))))
        }
        assertTrue(out.list().orEmpty().isEmpty())
    }

    @Test
    fun cancellingLeavesNoOutputAndNoWorkFolder() {
        val request = job(ConvertMode.PDF_TO_IMAGES, listOf(corpus("02_large_300pages.pdf")), pdfToImages = PdfToImagesOptions(dpi = 100))
        runBlocking {
            val running = launch(Dispatchers.Default) { runner.run(request) }
            var waited = 0
            while (out.list().orEmpty().isEmpty() && waited < 600) {
                delay(50)
                waited++
            }
            assertTrue("conversion never wrote a file", out.list().orEmpty().isNotEmpty())
            running.cancelAndJoin()
        }
        assertTrue("left behind: " + out.list().orEmpty().toList(), out.list().orEmpty().isEmpty())
        assertTrue(leftoverWorkFolders().isEmpty())
    }

    // ---- DocumentTreeSink ------------------------------------------------------------------------------------

    @Test
    fun theSinkNeverTouchesAnExistingFileAndDeletesOnlyWhatItCreated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(out, "keep.txt").writeText("precious")
        val sink = DocumentTreeSink(context, root)
        sink.open("keep.txt", "text/plain").use { it.write("new".toByteArray()) }
        assertEquals("precious", File(out, "keep.txt").readText())
        assertEquals(1, sink.createdNames.size)
        assertTrue(sink.createdNames[0] != "keep.txt")
        assertEquals("new", File(out, sink.createdNames[0]).readText())
        sink.delete("keep.txt")
        assertTrue(sink.createdNames.isEmpty())
        assertEquals(listOf("keep.txt"), out.list().orEmpty().toList())
        assertEquals("precious", File(out, "keep.txt").readText())
        sink.delete("never-created.txt") // must be harmless
    }
}
