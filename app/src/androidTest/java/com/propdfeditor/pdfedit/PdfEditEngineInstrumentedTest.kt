package com.propdfeditor.pdfedit

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.pdfedit.engine.EditRefusedException
import com.propdf.pdfedit.engine.EditSession
import com.propdf.pdfedit.engine.EditWarning
import com.propdf.pdfedit.engine.PdfEditEngine
import com.propdf.pdfedit.geometry.PointPds
import com.propdf.pdfedit.geometry.RectPds
import com.propdf.pdfedit.ops.AddImage
import com.propdf.pdfedit.ops.AddText
import com.propdf.pdfedit.ops.DeleteImage
import com.propdf.pdfedit.ops.FontChoice
import com.propdf.pdfedit.ops.ImageRef
import com.propdf.pdfedit.ops.TransformImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * End-to-end tests of the editing engine on real PDFs, using PDFBox-Android and the platform PdfRenderer.
 * Page geometry facts used below were derived by hand for a 612 x 792 page (see PageGeometryTest).
 */
@RunWith(AndroidJUnit4::class)
class PdfEditEngineInstrumentedTest {

    private lateinit var work: File
    private lateinit var engine: PdfEditEngine

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        PDFBoxResourceLoader.init(instrumentation.targetContext)
        // The bundled fonts are assets of the :pdfedit library, packaged into the test APK.
        engine = PdfEditEngine(instrumentation.targetContext) // the bundled fonts are assets of :pdfedit, packaged in the app APK
        work = File(instrumentation.targetContext.cacheDir, "pdfedit-test-" + System.nanoTime()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun smallBitmap(): Bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }

    /** Letter page; optional rotation, CropBox, text and up to two images drawn at known user-space rectangles. */
    private fun basePdf(
        name: String,
        rotation: Int = 0,
        crop: PDRectangle? = null,
        text: String? = null,
        images: Int = 0,
        nested: Boolean = false
    ): File {
        val file = File(work, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            page.rotation = rotation
            if (crop != null) page.cropBox = crop
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                if (text != null) {
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 740f)
                    cs.showText(text)
                    cs.endText()
                }
                for (i in 0 until images) {
                    val image = LosslessFactory.createFromImage(doc, smallBitmap())
                    if (nested) {
                        cs.saveGraphicsState()
                        cs.transform(Matrix(0.5f, 0f, 0f, 0.5f, 100f, 100f))
                        cs.drawImage(image, 100f, 400f, 200f, 100f)
                        cs.restoreGraphicsState()
                    } else {
                        cs.drawImage(image, 100f, 500f - i * 150f, 200f, 100f)
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun extract(f: File): String =
        PDDocument.load(f).use { PDFTextStripper().getText(it) }.replace(Regex("\\s+"), " ").trim()

    private fun assertBounds(expected: RectPds, actual: RectPds, label: String) {
        assertEquals("$label left", expected.left, actual.left, 0.05)
        assertEquals("$label bottom", expected.bottom, actual.bottom, 0.05)
        assertEquals("$label right", expected.right, actual.right, 0.05)
        assertEquals("$label top", expected.top, actual.top, 0.05)
    }

    /** Bounds in Page Display Space of the image drawn at user rect (100,500)-(300,600) on a 612 x 792 page. */
    private fun expectedFirstImage(rotation: Int): RectPds = when (rotation) {
        0 -> RectPds(100.0, 500.0, 300.0, 600.0)
        90 -> RectPds(500.0, 312.0, 600.0, 512.0)
        180 -> RectPds(312.0, 192.0, 512.0, 292.0)
        else -> RectPds(192.0, 100.0, 292.0, 300.0)
    }

    // ---- adding text -----------------------------------------------------------------------------------------

    @Test
    fun addedTextIsExtractableAndTheOriginalFileIsUntouched() {
        val base = basePdf("blank.pdf")
        val before = sha256(base)
        val out = File(work, "out.pdf")
        val report = engine.materialize(base, listOf(AddText("t1", 0, "Hello PDF 2026", PointPds(72.0, 700.0), 18.0)), out)
        assertTrue(extract(out).contains("Hello PDF 2026"))
        assertEquals(before, sha256(base))
        assertTrue("unexpected warnings: " + report.warnings, report.warnings.isEmpty())
        assertEquals(1, report.pageCount)
    }

    @Test
    fun addedTextIsUprightAtTheRequestedDisplayPositionOnEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val base = basePdf("rot$rotation.pdf", rotation = rotation)
            val out = File(work, "out$rotation.pdf")
            engine.materialize(base, listOf(AddText("t", 0, "Hello PDF 2026", PointPds(100.0, 400.0), 18.0)), out)

            val pfd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val displayW = if (rotation % 180 == 0) 612 else 792
                    val displayH = if (rotation % 180 == 0) 792 else 612
                    assertEquals("rot $rotation width", displayW, page.width)
                    assertEquals("rot $rotation height", displayH, page.height)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
                    for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                        val c = bmp.getPixel(x, y)
                        if (Color.red(c) < 100 && Color.green(c) < 100 && Color.blue(c) < 100) {
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                    bmp.recycle()
                    val baselineY = displayH - 400
                    val label = "rot $rotation box=($minX,$minY)-($maxX,$maxY)"
                    assertTrue("$label: no dark pixels", maxX >= 0)
                    assertTrue("$label left", minX in 95..110)
                    assertTrue("$label right", maxX in 180..300)
                    assertTrue("$label top", minY in (baselineY - 20)..(baselineY - 8))
                    assertTrue("$label bottom", maxY in (baselineY - 2)..(baselineY + 10))
                    assertTrue("$label text must be wider than tall", (maxX - minX) > (maxY - minY) * 3)
                }
            }
        }
    }

    @Test
    fun boldAndMultiLineTextWork() {
        val base = basePdf("blank2.pdf")
        val out = File(work, "out2.pdf")
        engine.materialize(
            base,
            listOf(AddText("t", 0, "First line\nSecond line", PointPds(72.0, 600.0), 14.0, FontChoice.BundledBold)),
            out
        )
        val text = extract(out)
        assertTrue(text, text.contains("First line") && text.contains("Second line"))
    }

    @Test
    fun charactersTheFontCannotDrawAreReplacedAndReported() {
        val base = basePdf("blank3.pdf")
        val out = File(work, "out3.pdf")
        val smiley = "\uD83D\uDE00"
        val report = engine.materialize(base, listOf(AddText("t", 0, "A $smiley B", PointPds(72.0, 600.0), 14.0)), out)
        assertTrue(report.warnings.contains(EditWarning.CHARACTERS_REPLACED))
        assertEquals(listOf(smiley), report.replacedCharacters["t"])
        assertTrue(extract(out).contains("A ? B"))
    }

    @Test
    fun aDeviceFontThatCannotBeLoadedFallsBackToTheBundledFont() {
        val base = basePdf("blank4.pdf")
        val out = File(work, "out4.pdf")
        val broken = FontChoice.DeviceFont("broken") { throw IOException("no such font") }
        val report = engine.materialize(base, listOf(AddText("t", 0, "Fallback works", PointPds(72.0, 600.0), 14.0, broken)), out)
        assertTrue(report.warnings.contains(EditWarning.FONT_UNAVAILABLE))
        assertTrue(extract(out).contains("Fallback works"))
    }

    // ---- images ----------------------------------------------------------------------------------------------

    @Test
    fun addedImageLandsOnTheRequestedBoundsOnEveryRotationAndWithACropBox() {
        val wanted = RectPds(50.0, 60.0, 150.0, 260.0)
        val cases = listOf(
            Pair(0, null), Pair(90, null), Pair(180, null), Pair(270, null),
            Pair(0, PDRectangle(50f, 60f, 400f, 600f)), Pair(90, PDRectangle(50f, 60f, 400f, 600f))
        )
        for ((index, case) in cases.withIndex()) {
            val base = basePdf("img$index.pdf", rotation = case.first, crop = case.second)
            val out = File(work, "imgout$index.pdf")
            engine.materialize(base, listOf(AddImage("i", 0, wanted) { png() }), out)
            val info = engine.inspect(out)
            val images = info.pages[0].images
            assertEquals("case $index image count", 1, images.size)
            assertBounds(wanted, images[0].bounds, "case $index")
        }
    }

    @Test
    fun existingImageCanBeMovedAndResizedOnEveryRotation() {
        val target = RectPds(200.0, 300.0, 320.0, 360.0)
        for (rotation in listOf(0, 90, 180, 270)) {
            val base = basePdf("move$rotation.pdf", rotation = rotation, images = 1)
            val before = engine.inspect(base).pages[0].images
            assertEquals(1, before.size)
            assertBounds(expectedFirstImage(rotation), before[0].bounds, "rot $rotation before")

            val out = File(work, "moveout$rotation.pdf")
            engine.materialize(base, listOf(TransformImage("m", 0, before[0].ref, target)), out)
            val after = engine.inspect(out).pages[0].images
            assertEquals("rot $rotation count", 1, after.size)
            assertBounds(target, after[0].bounds, "rot $rotation after")
        }
    }

    @Test
    fun imageDrawnUnderANestedMatrixIsMovedExactly() {
        val base = basePdf("nested.pdf", images = 1, nested = true)
        val before = engine.inspect(base).pages[0].images
        assertBounds(RectPds(150.0, 300.0, 250.0, 350.0), before[0].bounds, "before")
        val target = RectPds(10.0, 10.0, 110.0, 60.0)
        val out = File(work, "nestedout.pdf")
        engine.materialize(base, listOf(TransformImage("m", 0, before[0].ref, target)), out)
        assertBounds(target, engine.inspect(out).pages[0].images[0].bounds, "after")
    }

    @Test
    fun deletingOneImageKeepsTheOtherImageAndTheText() {
        val base = basePdf("two.pdf", text = "Keep this text", images = 2)
        val before = engine.inspect(base).pages[0].images
        assertEquals(2, before.size)
        val out = File(work, "twoout.pdf")
        engine.materialize(base, listOf(DeleteImage("d", 0, before[0].ref)), out)
        val after = engine.inspect(out).pages[0].images
        assertEquals(1, after.size)
        assertBounds(before[1].bounds, after[0].bounds, "remaining image")
        assertTrue(extract(out).contains("Keep this text"))
    }

    @Test
    fun aReferenceToAnImageThatIsNotThereIsSkippedWithAWarning() {
        val base = basePdf("stale.pdf", images = 1)
        val out = File(work, "staleout.pdf")
        val report = engine.materialize(base, listOf(DeleteImage("d", 0, ImageRef(0, 5, "Im99"))), out)
        assertEquals(EditWarning.STALE_IMAGE_REFERENCE, report.skippedOperations["d"])
        assertEquals(1, engine.inspect(out).pages[0].images.size)
    }

    // ---- session, undo/redo, safety ----------------------------------------------------------------------------

    @Test
    fun undoRemovesTheEditFromTheOutputAndRedoBringsItBack() {
        val base = basePdf("undo.pdf")
        val session = EditSession(engine, base)
        session.apply(AddText("t", 0, "Undo me please", PointPds(72.0, 600.0), 14.0))

        val first = File(work, "u1.pdf")
        session.materialize(first)
        assertTrue(extract(first).contains("Undo me please"))

        session.undo()
        val second = File(work, "u2.pdf")
        session.materialize(second)
        assertFalse(extract(second).contains("Undo me please"))

        session.redo()
        val third = File(work, "u3.pdf")
        session.materialize(third)
        assertTrue(extract(third).contains("Undo me please"))
    }

    @Test
    fun saveWritesTheTargetAtomicallyAndNeverTouchesTheBase() {
        val base = basePdf("save.pdf")
        val before = sha256(base)
        val session = EditSession(engine, base)
        session.apply(AddText("t", 0, "Saved text", PointPds(72.0, 600.0), 14.0))
        val target = File(work, "saved-copy.pdf")
        session.save(target, File(work, "tmp"))
        assertTrue(target.exists())
        assertTrue(extract(target).contains("Saved text"))
        assertEquals(before, sha256(base))
        assertFalse(File(work, ".saved-copy.pdf.part").exists())
        assertTrue(File(work, "tmp").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theOriginalFileCanNeverBeTheOutput() {
        val base = basePdf("same.pdf")
        val before = sha256(base)
        val op = AddText("t", 0, "x", PointPds(72.0, 600.0), 14.0)
        try {
            engine.materialize(base, listOf(op), base)
            fail("expected a refusal")
        } catch (e: EditRefusedException) {
            assertEquals(EditRefusedException.Reason.SAME_AS_SOURCE, e.reason)
        }
        val session = EditSession(engine, base)
        session.apply(op)
        try {
            session.save(base, File(work, "tmp2"))
            fail("expected a refusal")
        } catch (e: EditRefusedException) {
            assertEquals(EditRefusedException.Reason.SAME_AS_SOURCE, e.reason)
        }
        assertEquals(before, sha256(base))
    }

    @Test
    fun encryptedFilesAreRefused() {
        val file = File(work, "enc.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.LETTER))
            val policy = StandardProtectionPolicy("owner", "user", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(file)
        }
        try {
            engine.inspect(file)
            fail("expected a refusal")
        } catch (e: EditRefusedException) {
            assertEquals(EditRefusedException.Reason.ENCRYPTED, e.reason)
        }
    }

    @Test
    fun anOperationOnAPageThatDoesNotExistIsRefused() {
        val base = basePdf("range.pdf")
        try {
            engine.materialize(base, listOf(AddText("t", 3, "x", PointPds(1.0, 1.0), 12.0)), File(work, "rangeout.pdf"))
            fail("expected a refusal")
        } catch (e: EditRefusedException) {
            assertEquals(EditRefusedException.Reason.PAGE_OUT_OF_RANGE, e.reason)
        }
    }

    @Test
    fun invalidGeometryIsSkippedNotWritten() {
        val base = basePdf("invalid.pdf")
        val out = File(work, "invalidout.pdf")
        val report = engine.materialize(
            base,
            listOf(
                AddImage("flat", 0, RectPds(10.0, 10.0, 10.0, 50.0)) { png() },
                AddText("tiny", 0, "x", PointPds(10.0, 10.0), 0.0)
            ),
            out
        )
        assertEquals(EditWarning.EMPTY_BOUNDS, report.skippedOperations["flat"])
        assertEquals(EditWarning.EMPTY_BOUNDS, report.skippedOperations["tiny"])
        assertEquals(0, engine.inspect(out).pages[0].images.size)
    }
}
