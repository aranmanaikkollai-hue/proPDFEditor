package com.propdf.security.redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.security.data.database.SecurityDatabase
import com.propdf.security.data.repository.SecurityRepository
import com.propdf.security.encryption.PdfSecurityException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Random

/**
 * Phase 4 suite for secure redaction. Every fixture plants distinctive secrets in every place content
 * can hide, then the output is attacked through every recovery route the brief lists: a PDFBox text
 * extractor, a reachable-object walk over decoded streams and strings, raw file bytes,
 * annotations, form fields, metadata/outlines, images (decoded pixels), and rendering with the platform
 * PdfRenderer. A control test proves the same checks DO detect the old paint-a-rectangle behaviour.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxRedactionInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File           // served by TestPdfProvider as content://com.propdfeditor.test.pdfs/<name>
    private lateinit var engine: PdfBoxRedactionEngine

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "testpdfs").apply { deleteRecursively(); mkdirs() }
        engine = PdfBoxRedactionEngine(context)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private companion object {
        const val SECRET_VISIBLE = "SECRETNAME"
        const val SECRET_COVERED = "COVEREDTEXT"
        const val SECRET_OUTSIDE = "OUTSIDETEXT"
        const val SECRET_FORM = "FORMSECRET"
        const val SECRET_ANNOT = "ANNOTSECRET"
        const val SECRET_XOBJ = "XOBJSECRET"
        const val SECRET_META_TITLE = "METATITLESECRET"
        const val SECRET_META_AUTHOR = "METAAUTHORSECRET"
        const val SECRET_XMP = "XMPSECRET"
        const val SECRET_OUTLINE = "OUTLINESECRET"
        val ALL_SECRETS = listOf(
            SECRET_VISIBLE, SECRET_COVERED, SECRET_OUTSIDE, SECRET_FORM, SECRET_ANNOT, SECRET_XOBJ,
            SECRET_META_TITLE, SECRET_META_AUTHOR, SECRET_XMP, SECRET_OUTLINE
        )
        const val KEPT1 = "KEPTPAGEONE"
        const val KEPT3 = "KEPTPAGETHREE"
    }

    // ------------------------------------------------------------------ fixture

    /**
     * 3 pages. Page 2 holds every kind of secret; pages 1 and 3 are untouched pages that deliberately share a
     * resource dictionary with page 2 and link to it. Page 2's visible text sits in the area (50, 600, 300 x 60).
     */
    private fun fixture(name: String = "secrets.pdf"): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val p1 = PDPage(PDRectangle.A4); val p2 = PDPage(PDRectangle.A4); val p3 = PDPage(PDRectangle.A4)

            // Shared resources: p1, p2 (and p3) point at ONE dictionary that will also list a form XObject only p2 draws.
            val shared = PDResources()
            p1.resources = shared; p2.resources = shared; p3.resources = shared
            doc.addPage(p1); doc.addPage(p2); doc.addPage(p3)

            PDPageContentStream(doc, p1).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(50f, 700f)
                cs.showText(KEPT1); cs.endText()
            }
            PDPageContentStream(doc, p3).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(50f, 700f)
                cs.showText(KEPT3); cs.endText()
            }

            // Form XObject with secret text, drawn only on page 2 (so it lands in the shared resources).
            val form = PDFormXObject(doc)
            form.bBox = PDRectangle(0f, 0f, 200f, 30f)
            val formRes = PDResources(); form.resources = formRes
            formRes.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
            form.stream.createOutputStream().use { it.write("BT /F1 12 Tf 5 10 Td ($SECRET_XOBJ) Tj ET".toByteArray()) }

            val noise = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val rnd = Random(42)
            for (x in 0 until 100) for (y in 0 until 100) noise.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
            val image = LosslessFactory.createFromImage(doc, noise)

            PDPageContentStream(doc, p2).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 24f); cs.newLineAtOffset(60f, 620f)
                cs.showText(SECRET_VISIBLE); cs.endText()
                // text hidden under a white rectangle
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 500f)
                cs.showText(SECRET_COVERED); cs.endText()
                cs.setNonStrokingColor(255, 255, 255); cs.addRect(50f, 490f, 250f, 30f); cs.fill()
                // text outside any marked area
                cs.setNonStrokingColor(0, 0, 0)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(60f, 300f)
                cs.showText(SECRET_OUTSIDE); cs.endText()
                cs.drawImage(image, 350f, 560f, 100f, 100f)
                cs.drawForm(form)
            }
            noise.recycle()

            p2.annotations = listOf(
                PDAnnotationText().apply { contents = SECRET_ANNOT; rectangle = PDRectangle(300f, 650f, 20f, 20f) },
                PDAnnotationLink().apply { rectangle = PDRectangle(60f, 600f, 100f, 20f) }
            )

            // links on the kept pages point at the page that gets redacted
            val dest = PDPageXYZDestination().apply { page = p2 }
            p1.annotations = listOf(PDAnnotationLink().apply { rectangle = PDRectangle(50f, 690f, 120f, 25f); destination = dest })
            p3.annotations = listOf(PDAnnotationLink().apply {
                rectangle = PDRectangle(50f, 690f, 120f, 25f); action = PDActionGoTo().apply { destination = PDPageXYZDestination().apply { page = p2 } }
            })

            // AcroForm: a value on the redacted page, plus an unrelated field on a kept page
            val form2 = PDAcroForm(doc); doc.documentCatalog.acroForm = form2
            form2.defaultResources = PDResources().also { it.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form2.defaultAppearance = "/Helv 12 Tf 0 g"
            fun field(nameStr: String, value: String, page: PDPage, rect: PDRectangle) {
                val f = PDTextField(form2); f.partialName = nameStr; f.defaultAppearance = "/Helv 12 Tf 0 g"
                form2.fields.add(f)
                val w: PDAnnotationWidget = f.widgets[0]; w.rectangle = rect; w.page = page
                page.annotations = page.annotations + w
                f.value = value
            }
            field("ssn", SECRET_FORM, p2, PDRectangle(60f, 400f, 200f, 24f))
            field("other", "KEPTFORMVALUE", p3, PDRectangle(60f, 600f, 200f, 24f))

            // Metadata: Info, XMP, bookmarks
            doc.documentInformation.title = SECRET_META_TITLE
            doc.documentInformation.author = SECRET_META_AUTHOR
            val xmp = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?><x:xmpmeta xmlns:x='adobe:ns:meta/'>" +
                "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' " +
                "xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:description>$SECRET_XMP</dc:description></rdf:Description>" +
                "</rdf:RDF></x:xmpmeta><?xpacket end='w'?>"
            val meta = PDMetadata(doc, ByteArrayInputStream(xmp.toByteArray()))
            doc.documentCatalog.metadata = meta
            val outline = PDDocumentOutline(); doc.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = SECRET_OUTLINE; destination = PDPageXYZDestination().apply { page = p2 } })

            doc.save(file)
        }
        return file
    }

    private val secretArea = RedactionArea(2, 50f, 600f, 300f, 60f)           // over SECRETNAME, (and the link)
    private val allAreasOnP2 = listOf(
        secretArea,
        RedactionArea(2, 50f, 490f, 250f, 30f),                                 // covered text
        RedactionArea(2, 340f, 550f, 130f, 130f),                               // the image + annotation
        RedactionArea(2, 50f, 390f, 230f, 50f)                                  // form widget
    )

    // ------------------------------------------------------------------ independent inspection helpers

    private fun pdfboxText(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    private fun pdfboxPageText(file: File, page: Int): String = PDDocument.load(file).use { d ->
        PDFTextStripper().also { it.startPage = page; it.endPage = page }.getText(d)
    }

    /** Everything reachable in the file as lowercase text: strings plus decoded non-image streams. Written independently of the engine. */
    private fun reachableText(file: File): String {
        val sb = StringBuilder()
        PDDocument.load(file).use { doc ->
            val seen = IdentityHashMap<Any, Boolean>()
            val stack = ArrayList<COSBase>(); stack.add(doc.document.trailer)
            while (stack.isNotEmpty()) {
                var n: COSBase = stack.removeAt(stack.size - 1)
                if (n is COSObject) n = n.getObject() ?: continue
                if (seen.put(n, true) != null) continue
                when (n) {
                    is COSString -> sb.append(n.string).append('\n')
                    is COSArray -> for (i in 0 until n.size()) n.get(i)?.let { stack.add(it) }
                    is COSDictionary -> {
                        for (k in n.keySet()) n.getItem(k)?.let { stack.add(it) }
                        if (n is COSStream && n.getNameAsString(COSName.SUBTYPE) != "Image") {
                            try {
                                val b = n.createInputStream().use { it.readBytes() }
                                sb.append(String(b, Charsets.ISO_8859_1)).append('\n').append(String(b, Charsets.UTF_16BE)).append('\n')
                            } catch (_: Exception) { }
                        }
                    }
                    else -> {}
                }
            }
        }
        return sb.toString().lowercase()
    }

    private fun rawBytesText(file: File): String = String(file.readBytes(), Charsets.ISO_8859_1).lowercase()

    private fun imageStreamCount(file: File): List<Pair<Int, Int>> = PDDocument.load(file).use { doc ->
        val out = ArrayList<Pair<Int, Int>>(); val seen = IdentityHashMap<Any, Boolean>()
        val stack = ArrayList<COSBase>(); stack.add(doc.document.trailer)
        while (stack.isNotEmpty()) {
            var n: COSBase = stack.removeAt(stack.size - 1)
            if (n is COSObject) n = n.getObject() ?: continue
            if (seen.put(n, true) != null) continue
            when (n) {
                is COSArray -> for (i in 0 until n.size()) n.get(i)?.let { stack.add(it) }
                is COSDictionary -> {
                    for (k in n.keySet()) n.getItem(k)?.let { stack.add(it) }
                    if (n is COSStream && n.getNameAsString(COSName.SUBTYPE) == "Image") out.add(n.getInt(COSName.WIDTH) to n.getInt(COSName.HEIGHT))
                }
                else -> {}
            }
        }
        out
    }

    private fun render(file: File, pageIndex: Int, scale: Float = 1f): Bitmap {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                r.openPage(pageIndex).use { p ->
                    val bmp = Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        } finally { pfd.close() }
    }

    private fun pageCountRenderer(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { return PdfRenderer(pfd).use { it.pageCount } } finally { pfd.close() }
    }

    private fun out(name: String) = File(dir, name)
    private fun tmpLeftovers() = (dir.listFiles()?.toList().orEmpty() + context.cacheDir.listFiles()?.toList().orEmpty())
        .filter { (it.name.startsWith(".") && it.name.endsWith(".tmp")) || it.name.startsWith("pdf_redact_") }

    private fun assertNoSecrets(file: File, secrets: List<String> = ALL_SECRETS) {
        val texts = mapOf("pdfbox" to pdfboxText(file), "objects" to reachableText(file), "raw" to rawBytesText(file))
        for ((route, text) in texts) for (s in secrets) {
            assertFalse("secret '$s' still recoverable through $route", text.lowercase().contains(s.lowercase()))
        }
    }

    // ------------------------------------------------------------------ control: the OLD behaviour is detected as insecure

    @Test fun control_paintingARectangleLeavesEverythingRecoverable_andTheChecksNotice() {
        val src = fixture("control-src.pdf")
        val covered = out("control-covered.pdf")
        PDDocument.load(src).use { doc ->
            PDPageContentStream(doc, doc.getPage(1), PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.setNonStrokingColor(0, 0, 0); cs.addRect(50f, 600f, 300f, 60f); cs.fill()
            }
            doc.save(covered)
        }
        assertTrue(pdfboxText(covered).contains(SECRET_VISIBLE))
        assertTrue(reachableText(covered).contains(SECRET_VISIBLE.lowercase()))
    }

    // ------------------------------------------------------------------ the main security property

    @Test fun everyRecoveryRoute_findsNothing_afterRedaction() = runBlocking<Unit> {
        val src = fixture(); val before = src.readBytes()
        for (s in ALL_SECRETS) assertTrue("fixture must really contain $s", reachableText(src).contains(s.lowercase()) || pdfboxText(src).contains(s))

        val dst = out("redacted.pdf")
        val report = engine.redact(src, dst, allAreasOnP2)

        assertTrue("source untouched", before.contentEquals(src.readBytes()))
        assertEquals(3, report.totalPages); assertEquals(listOf(2), report.redactedPages); assertEquals(2, report.keptPages)
        assertEquals(3, PDDocument.load(dst).use { it.numberOfPages }); assertEquals(3, pageCountRenderer(dst))

        // text extraction, search, copy/paste (the same text layer), decoded objects, raw bytes, annotations, forms, metadata, outline
        assertNoSecrets(dst)

        PDDocument.load(dst).use { d ->
            assertTrue("page 2 has no annotations", d.getPage(1).annotations.isEmpty())
            assertTrue("form is not carried over", d.documentCatalog.acroForm == null)
            assertTrue("no outline", d.documentCatalog.documentOutline == null)
            assertTrue("no XMP", d.documentCatalog.metadata == null)
            val info = d.documentInformation
            assertTrue(info.title == null && info.author == null && info.subject == null && info.keywords == null)
            val cat = d.documentCatalog.cosObject
            for (k in listOf("Names", "Dests", "OpenAction", "AA", "OCProperties", "StructTreeRoot")) assertFalse(k, cat.containsKey(COSName.getPDFName(k)))
        }
        // the 100x100 noise image must be gone; only the page-sized image of the redacted page exists
        val images = imageStreamCount(dst)
        assertEquals("exactly one image in the file", 1, images.size)
        assertTrue(images[0].first > 100 && images[0].second > 100)
        assertTrue(tmpLeftovers().isEmpty())
    }

    @Test fun keptPages_areUnchanged_andTheirLinksNoLongerPointAtRemovedPages() = runBlocking<Unit> {
        val src = fixture(); val dst = out("kept.pdf")
        engine.redact(src, dst, allAreasOnP2)
        assertEquals(pdfboxPageText(src, 1).trim(), pdfboxPageText(dst, 1).trim())
        assertEquals(pdfboxPageText(src, 3).trim(), pdfboxPageText(dst, 3).trim())
        assertTrue(pdfboxPageText(dst, 1).contains(KEPT1)); assertTrue(pdfboxPageText(dst, 3).contains(KEPT3))
        PDDocument.load(dst).use { d ->
            for (i in listOf(0, 2)) for (a in d.getPage(i).annotations) {
                val c = a.cosObject
                for (k in listOf("Dest", "P", "Parent")) assertFalse("page ${i + 1} annotation still has /$k", c.containsKey(COSName.getPDFName(k)))
                val action = c.getDictionaryObject(COSName.A) as? COSDictionary
                assertTrue(action == null || action.getNameAsString(COSName.S) == "URI")
            }
            // the form XObject only the redacted page drew must not survive in the SHARED resources
            val res = d.getPage(0).resources
            assertTrue("kept page keeps no unused XObjects", res.xObjectNames.toList().isEmpty())
        }
        // kept pages still render with visible ink
        for (i in listOf(0, 2)) {
            val bmp = render(dst, i); var ink = 0
            for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) if (bmp.getPixel(x, y) != Color.WHITE) ink++
            bmp.recycle(); assertTrue("page ${i + 1} renders content", ink > 20)
        }
    }

    @Test fun redactedPage_isOneImage_noTextNoFontsNoAnnotations_andStillRendersTheRestOfThePage() = runBlocking<Unit> {
        val dst = out("structure.pdf")
        engine.redact(fixture(), dst, allAreasOnP2)
        PDDocument.load(dst).use { d ->
            val page = d.getPage(1)
            assertTrue(PDFTextStripper().also { it.startPage = 2; it.endPage = 2 }.getText(d).isBlank())
            assertFalse(page.resources.fontNames.iterator().hasNext())
            assertEquals(1, page.resources.xObjectNames.toList().size)
        }
        // area is black on screen; unmarked part of the page is still drawn (the text OUTSIDE the areas is now pixels, not text)
        val bmp = render(dst, 1)
        val h = bmp.height
        for (px in listOf(60 to (h - 640), 200 to (h - 630), 340 + 60 to (h - 600))) {
            assertEquals("black inside area at $px", Color.BLACK, bmp.getPixel(px.first, px.second))
        }
        var ink = 0
        for (y in (h - 320) until (h - 285)) for (x in 55 until 200) if (bmp.getPixel(x, y) != Color.WHITE) ink++
        bmp.recycle()
        assertTrue("text outside the areas is still visible as pixels", ink > 30)
    }

    // ------------------------------------------------------------------ coordinates: rotation / CropBox / MediaBox offset

    @Test fun areas_landOnTheMarkedPixels_forEveryRotationAndOffsetBox() = runBlocking<Unit> {
        for (rotation in listOf(0, 90, 180, 270)) for (offset in listOf(0f, 40f)) {
            val src = out("rot-$rotation-$offset.pdf")
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(offset, offset, 200f, 300f)); page.rotation = rotation
                if (offset > 0f) page.cropBox = PDRectangle(offset + 10f, offset + 10f, 180f, 280f)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(255, 0, 0); cs.addRect(offset, offset, 200f, 300f); cs.fill()
                    cs.setNonStrokingColor(0, 0, 255); cs.addRect(offset + 60f, offset + 90f, 30f, 30f); cs.fill()
                }
                doc.save(src)
            }
            // find the blue patch ON SCREEN with the platform renderer (independent of the engine's own geometry)
            val shot = render(src, 0)
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            for (y in 0 until shot.height) for (x in 0 until shot.width) {
                val c = shot.getPixel(x, y)
                if (Color.blue(c) > 200 && Color.red(c) < 60) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
            }
            val w = shot.width; val h = shot.height; shot.recycle()
            assertTrue("rot $rotation off $offset: patch visible", maxX > minX && maxY > minY)
            val area = RedactionArea(1, (minX - 3).toFloat(), (h - (maxY + 4)).toFloat(), (maxX - minX + 7).toFloat(), (maxY - minY + 7).toFloat())

            val dst = out("rot-out-$rotation-$offset.pdf")
            engine.redact(src, dst, listOf(area))
            val after = render(dst, 0)
            assertEquals("same on-screen size", w, after.width); assertEquals(h, after.height)
            var blue = 0
            for (y in 0 until after.height) for (x in 0 until after.width) { val c = after.getPixel(x, y); if (Color.blue(c) > 200 && Color.red(c) < 60) blue++ }
            assertEquals("rot $rotation off $offset: the marked patch is gone", 0, blue)
            assertEquals(Color.BLACK, after.getPixel((minX + maxX) / 2, (minY + maxY) / 2))
            val far = after.getPixel(w - 6, 6)
            assertTrue("rot $rotation off $offset: rest of page still red", Color.red(far) > 200 && Color.blue(far) < 60)
            after.recycle()
        }
    }

    // ------------------------------------------------------------------ images

    @Test fun imageUnderTheArea_originalPixelsAreNotInTheOutput() = runBlocking<Unit> {
        val src = out("img-src.pdf")
        val noise = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        for (x in 0 until 200) for (y in 0 until 200) noise.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 300f)); doc.addPage(page)
            val image = LosslessFactory.createFromImage(doc, noise)
            PDPageContentStream(doc, page).use { it.drawImage(image, 50f, 50f, 200f, 200f) }
            doc.save(src)
        }
        val before = imageStreamCount(src); assertEquals(listOf(200 to 200), before)
        val dst = out("img-out.pdf")
        // mark the whole left half of the image
        engine.redact(src, dst, listOf(RedactionArea(1, 50f, 50f, 100f, 200f)))
        val after = imageStreamCount(dst)
        assertEquals("only the rebuilt page image remains", 1, after.size)
        assertTrue("the 200x200 original image is gone", after[0] != (200 to 200))

        val bmp = render(dst, 0)
        // decode through the renderer: left half black, right half still noise (visible content kept as pixels)
        var blackLeft = 0; var total = 0
        for (y in 60 until 240) for (x in 55 until 145) { total++; if (bmp.getPixel(x, y) == Color.BLACK) blackLeft++ }
        assertEquals("every pixel inside the area is black", total, blackLeft)
        var colourful = 0
        for (y in 60 until 240) for (x in 160 until 245) if (bmp.getPixel(x, y) != Color.BLACK && bmp.getPixel(x, y) != Color.WHITE) colourful++
        bmp.recycle(); noise.recycle()
        assertTrue("unmarked half of the image is preserved visually", colourful > 5000)
    }

    @Test fun label_isWhiteOnBlack_andNoTextLayerIsCreated() = runBlocking<Unit> {
        val dst = out("label.pdf")
        engine.redact(fixture(), dst, listOf(RedactionArea(2, 50f, 600f, 300f, 60f, label = "REDACTED")))
        assertFalse(pdfboxPageText(dst, 2).contains("REDACTED"))      // the label is pixels, not text
        assertNoSecrets(dst, listOf(SECRET_VISIBLE, SECRET_COVERED, SECRET_OUTSIDE, SECRET_XOBJ, SECRET_ANNOT, SECRET_FORM))
        val bmp = render(dst, 1); val h = bmp.height
        var white = 0; var black = 0
        for (y in (h - 660) until (h - 600)) for (x in 50 until 350) { val c = bmp.getPixel(x, y); if (c == Color.WHITE) white++ else if (c == Color.BLACK) black++ }
        bmp.recycle()
        assertTrue(black > 10000); assertTrue("label pixels present", white > 20)
    }

    // ------------------------------------------------------------------ refusals and failure safety

    private fun encryptedFixture(): File {
        val f = out("enc.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle.A4))
            d.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            d.save(f)
        }
        return f
    }

    private fun signedFixture(): File {
        val base = out("sign-base.pdf"); PDDocument().use { it.addPage(PDPage(PDRectangle.A4)); it.save(base) }
        val signed = out("signed.pdf"); val tmp = out("signed.tmp0")
        PDDocument.load(base).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE); setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only"); setSignDate(Calendar.getInstance())
            }
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            FileOutputStream(tmp).use { doc.saveIncremental(it) }
        }
        tmp.copyTo(signed, overwrite = true); tmp.delete()
        return signed
    }

    @Test fun refusals_publishNothing_andLeaveTheSourceIntact() = runBlocking<Unit> {
        val ok = listOf(RedactionArea(1, 10f, 10f, 50f, 50f))
        val enc = encryptedFixture(); val signed = signedFixture(); val plain = fixture("refuse.pdf")
        val garbage = File(dir, "garbage.pdf").apply { writeText("not a pdf") }
        val empty = File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        val cases: List<Triple<String, File, List<RedactionArea>>> = listOf(
            Triple("encrypted", enc, ok), Triple("signed", signed, ok), Triple("garbage", garbage, ok), Triple("empty", empty, ok),
            Triple("missing", File(dir, "nope.pdf"), ok), Triple("no areas", plain, emptyList()),
            Triple("page out of range", plain, listOf(RedactionArea(9, 10f, 10f, 50f, 50f))),
            Triple("page 0", plain, listOf(RedactionArea(0, 10f, 10f, 50f, 50f))),
            Triple("zero size", plain, listOf(RedactionArea(1, 10f, 10f, 0f, 50f))),
            Triple("fully outside", plain, listOf(RedactionArea(1, 1000f, 1000f, 50f, 50f))),
            Triple("NaN", plain, listOf(RedactionArea(1, Float.NaN, 10f, 50f, 50f)))
        )
        for ((label, file, areas) in cases) {
            val before = if (file.exists()) file.readBytes() else null
            val dst = out("refused-${label.replace(' ', '_')}.pdf")
            try { engine.redact(file, dst, areas); fail("$label must be refused") } catch (e: Exception) { }
            assertFalse("$label: nothing published", dst.exists())
            if (before != null) assertTrue("$label: source untouched", before.contentEquals(file.readBytes()))
        }
        try { engine.redact(enc, out("e2.pdf"), ok); fail() } catch (e: PdfSecurityException.RedactionProtectedSource) { }
        try { engine.redact(signed, out("s2.pdf"), ok); fail() } catch (e: PdfSecurityException.RedactionSignedDocument) { }
        try { engine.redact(plain, out("n2.pdf"), emptyList()); fail() } catch (e: PdfSecurityException.RedactionNoRegions) { }
        try { engine.redact(plain, out("i2.pdf"), listOf(RedactionArea(9, 1f, 1f, 5f, 5f))); fail() } catch (e: PdfSecurityException.RedactionInvalidRegion) { }
        assertTrue(tmpLeftovers().isEmpty())
    }

    @Test fun cancellation_publishesNothing_andLeavesNoTempFiles() {
        val src = fixture("cancel-src.pdf"); val dst = out("cancel-out.pdf")
        val job = GlobalScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            engine.redact(src, dst, allAreasOnP2, PdfBoxRedactionEngine.Options(dpi = 300))
        }
        runBlocking { job.cancelAndJoin() }
        if (job.isCancelled) assertFalse(dst.exists())
        assertTrue(tmpLeftovers().isEmpty())
    }

    @Test fun multiplePagesAndAreas_eachRedactedPageIsVerified() = runBlocking<Unit> {
        val src = out("multi.pdf")
        PDDocument().use { doc ->
            for (i in 1..4) {
                val p = PDPage(PDRectangle.A5); doc.addPage(p)
                PDPageContentStream(doc, p).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f); cs.newLineAtOffset(30f, 300f); cs.showText("PAGE${i}SECRET"); cs.endText()
                }
            }
            doc.save(src)
        }
        val dst = out("multi-out.pdf")
        val report = engine.redact(src, dst, listOf(RedactionArea(1, 20f, 290f, 120f, 30f), RedactionArea(3, 20f, 290f, 120f, 30f), RedactionArea(3, 20f, 100f, 50f, 50f)))
        assertEquals(listOf(1, 3), report.redactedPages); assertEquals(2, report.keptPages)
        val text = pdfboxText(dst)
        assertFalse(text.contains("PAGE1SECRET")); assertFalse(text.contains("PAGE3SECRET"))
        assertTrue(text.contains("PAGE2SECRET")); assertTrue(text.contains("PAGE4SECRET"))
        assertEquals(4, pageCountRenderer(dst))
    }

    // ------------------------------------------------------------------ wrapper + SAF through the repository

    private fun contentUri(f: File): Uri = Uri.parse("content://com.propdfeditor.test.pdfs/${f.name}")

    private fun repository(): Pair<SecurityRepository, SecurityDatabase> {
        val db = Room.inMemoryDatabaseBuilder(context, SecurityDatabase::class.java).allowMainThreadQueries().build()
        return SecurityRepository(context, db.securityOperationDao(), db.redactionDao(), db.secureDocumentDao()) to db
    }

    @Test fun repository_contentUris_roundTrip_marksAppliedOnlyAfterSuccess() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = fixture("saf-src.pdf"); val dest = File(dir, "saf-dest.pdf").apply { writeBytes(ByteArray(0)) }
            val uri = contentUri(src).toString()
            for (a in allAreasOnP2) repo.addRedaction(uri, a.pageNumber, RectF(a.left, a.bottom - a.height, a.left + a.width, a.bottom))
            // RectF follows the app convention: left/bottom = lower-left corner, width()/height() = size
            val r = repo.applyRedactions(contentUri(src), contentUri(dest), permanent = false)   // the flag is ignored: always secure
            assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
            assertTrue(dest.length() > 0); assertNoSecrets(dest)
            assertTrue("marks consumed", db.redactionDao().getPendingRedactions(uri).first().isEmpty())
        } finally { db.close() }
        assertTrue(context.cacheDir.listFiles { f -> f.name.startsWith("pdf_redact_") }.isNullOrEmpty())
    }

    @Test fun repository_failure_leavesDestinationEmpty_andKeepsPendingMarks() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val enc = encryptedFixture(); val dest = File(dir, "saf-fail.pdf").apply { writeBytes(ByteArray(0)) }
            val uri = contentUri(enc).toString()
            repo.addRedaction(uri, 1, RectF(10f, 100f, 60f, 50f))
            val r = repo.applyRedactions(contentUri(enc), contentUri(dest))
            assertTrue(r.exceptionOrNull() is PdfSecurityException.RedactionProtectedSource)
            assertEquals("destination untouched", 0L, dest.length())
            assertEquals("marks kept for a retry", 1, db.redactionDao().getPendingRedactions(uri).first().size)
            val none = repo.applyRedactions(contentUri(fixture("saf-none.pdf")), contentUri(dest))
            assertTrue(none.exceptionOrNull() is PdfSecurityException.RedactionNoRegions)
        } finally { db.close() }
    }

    @Test fun redactTextWrapper_removesTheTextFromMatchingPages_overContentUri() = runBlocking<Unit> {
        val src = fixture("wrap-src.pdf"); val dst = out("wrap-out.pdf")
        val r = RedactionEngine(context).redactText(contentUri(src), dst, SECRET_VISIBLE.lowercase(), caseSensitive = false)
        assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
        assertNoSecrets(dst)
        assertTrue(pdfboxText(dst).contains(KEPT1))
        val none = RedactionEngine(context).redactText(contentUri(src), out("wrap-none.pdf"), "no such text anywhere")
        assertTrue(none.isSuccess)
        assertTrue(pdfboxText(out("wrap-none.pdf")).contains(SECRET_VISIBLE))   // nothing matched: unchanged copy
    }
}
