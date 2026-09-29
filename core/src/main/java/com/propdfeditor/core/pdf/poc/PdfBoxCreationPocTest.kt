package com.propdfeditor.core.pdf.poc

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * PHASE 4A / POC A - PDF creation with PDFBox Android 2.0.27.0.
 * ISOLATED: does not touch PdfCreator.kt or any production class. iText stays in place.
 * Mirrors what scanner PdfCreator does today: bitmap(s) -> one PDF page each, A4/LETTER/LEGAL/AUTO.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxCreationPocTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            PocSupport.initPdfBox()
        }
    }

    private val temps = mutableListOf<File>()

    private fun newTemp(tag: String): File = PocSupport.tempPdf(tag).also { temps.add(it) }

    @After
    fun cleanup() {
        temps.forEach { it.delete() }
        temps.clear()
    }

    /** Adds a page of [pageSize] and draws [image] with the documented fit-inside algorithm. */
    private fun addImagePage(
        doc: PDDocument,
        pageSize: PDRectangle,
        image: PDImageXObject,
        margin: Float
    ): PocSupport.Placement {
        val page = PDPage(pageSize)
        doc.addPage(page)
        val p = PocSupport.fitInside(image.width, image.height, pageSize.width, pageSize.height, margin)
        PDPageContentStream(doc, page).use { cs -> cs.drawImage(image, p.x, p.y, p.w, p.h) }
        return p
    }

    // A1 ---------------------------------------------------------------------------------------
    @Test
    fun a1_blankPdf_opensWithValidMediaBox() {
        val file = newTemp("a1")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
        PDDocument.load(file).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val box = doc.getPage(0).mediaBox
            assertEquals(PDRectangle.A4.width, box.width, 0.5f)
            assertEquals(PDRectangle.A4.height, box.height, 0.5f)
        }
    }

    // A2 ---------------------------------------------------------------------------------------
    @Test
    fun a2_multiPagePdf_allPagesReopen() {
        val file = newTemp("a2")
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage(PDRectangle.A4)) }
            doc.save(file)
        }
        PDDocument.load(file).use { doc ->
            assertEquals(3, doc.numberOfPages)
            for (i in 0 until 3) {
                val box = doc.getPage(i).mediaBox
                assertTrue("page $i width", box.width > 0f)
                assertTrue("page $i height", box.height > 0f)
            }
        }
    }

    // A3 ---------------------------------------------------------------------------------------
    @Test
    fun a3_jpegToPdf() {
        val file = newTemp("a3")
        val bmp = PocSupport.testBitmap(800, 600, Color.rgb(200, 220, 255))
        try {
            PDDocument().use { doc ->
                val image = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                addImagePage(doc, PDRectangle.A4, image, 36f)
                doc.save(file)
            }
        } finally {
            bmp.recycle()
        }
        PDDocument.load(file).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(1, PocSupport.imageCount(doc.getPage(0)))
            val img = PocSupport.firstImage(doc.getPage(0))
            assertNotNull(img)
            assertEquals(800, img!!.width)
            assertEquals(600, img.height)
        }
    }

    // A4 ---------------------------------------------------------------------------------------
    @Test
    fun a4_pngToPdf_lossless() {
        val file = newTemp("a4")
        val bmp = PocSupport.testBitmap(640, 480, Color.rgb(255, 240, 200))
        try {
            PDDocument().use { doc ->
                val image = LosslessFactory.createFromImage(doc, bmp)
                addImagePage(doc, PDRectangle.A4, image, 36f)
                doc.save(file)
            }
        } finally {
            bmp.recycle()
        }
        PDDocument.load(file).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val img = PocSupport.firstImage(doc.getPage(0))
            assertNotNull(img)
            assertEquals(640, img!!.width)
            assertEquals(480, img.height)
            // Rendering exercises the embedded image object end-to-end (no corrupt reference).
            val render = PocSupport.render(doc, 0)
            try {
                assertTrue("rendered page should contain ink", PocSupport.darkness(render) > 0L)
            } finally {
                render.recycle()
            }
        }
    }

    /**
     * A4b - the PRODUCTION data path: PdfCreator compresses a Bitmap to encoded bytes (PNG when
     * quality == 100, otherwise JPEG) and feeds bytes to the PDF library. This isolates whether
     * PDImageXObject.createFromByteArray works for both encodings on Android.
     */
    @Test
    fun a4b_encodedBytesToPdf_pngAndJpeg() {
        val bmp = PocSupport.testBitmap(500, 700, Color.rgb(230, 255, 230))
        try {
            for ((label, bytes) in listOf(
                "png" to PocSupport.pngBytes(bmp),
                "jpeg" to PocSupport.jpegBytes(bmp, 80)
            )) {
                val file = newTemp("a4b_$label")
                PDDocument().use { doc ->
                    val image = PDImageXObject.createFromByteArray(doc, bytes, "poc_$label")
                    addImagePage(doc, PDRectangle.A4, image, 36f)
                    doc.save(file)
                }
                PDDocument.load(file).use { doc ->
                    assertEquals(label, 1, doc.numberOfPages)
                    val img = PocSupport.firstImage(doc.getPage(0))
                    assertNotNull(label, img)
                    assertEquals(label, 500, img!!.width)
                    assertEquals(label, 700, img.height)
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    // A5 ---------------------------------------------------------------------------------------
    @Test
    fun a5_multipleImages_onePagePerImage() {
        val file = newTemp("a5")
        val sizes = listOf(300 to 400, 600 to 300, 500 to 500)
        val colors = listOf(Color.rgb(255, 200, 200), Color.rgb(200, 255, 200), Color.rgb(200, 200, 255))
        PDDocument().use { doc ->
            sizes.forEachIndexed { i, (w, h) ->
                val bmp = PocSupport.testBitmap(w, h, colors[i])
                try {
                    val image = LosslessFactory.createFromImage(doc, bmp)
                    addImagePage(doc, PDRectangle.A4, image, 36f)
                } finally {
                    bmp.recycle() // one full-res bitmap alive at a time
                }
            }
            doc.save(file)
        }
        PDDocument.load(file).use { doc ->
            assertEquals(3, doc.numberOfPages)
            sizes.forEachIndexed { i, (w, h) ->
                val page = doc.getPage(i)
                assertEquals("page $i image count", 1, PocSupport.imageCount(page))
                val img = PocSupport.firstImage(page)!!
                assertEquals("page $i width", w, img.width)
                assertEquals("page $i height", h, img.height)
            }
        }
    }

    // A6 ---------------------------------------------------------------------------------------
    @Test
    fun a6_scaling_preservesAspectRatio_andStaysInsidePage() {
        val file = newTemp("a6")
        val margin = 36f
        val bmp = PocSupport.testBitmap(4000, 500, Color.rgb(255, 220, 180))
        val placement: PocSupport.Placement
        try {
            val pageSize = PDRectangle.A4
            var p: PocSupport.Placement? = null
            PDDocument().use { doc ->
                val image = JPEGFactory.createFromImage(doc, bmp, 0.7f)
                p = addImagePage(doc, pageSize, image, margin)
                doc.save(file)
            }
            placement = p!!
        } finally {
            bmp.recycle()
        }
        // Analytical checks on the deterministic algorithm.
        assertEquals(4000f / 500f, placement.w / placement.h, 0.01f)
        assertTrue(placement.x >= margin - 0.01f)
        assertTrue(placement.y >= margin - 0.01f)
        assertTrue(placement.x + placement.w <= PDRectangle.A4.width - margin + 0.01f)
        assertTrue(placement.y + placement.h <= PDRectangle.A4.height - margin + 0.01f)

        // Render check: ink inside the placement, no ink in the top 10pt strip (image not stretched
        // to fill the page).
        PDDocument.load(file).use { doc ->
            val render = PocSupport.render(doc, 0)
            try {
                val inside = PocSupport.inkInPdfRect(render, placement.x, placement.y, placement.w, placement.h)
                val topStrip = PocSupport.inkInPdfRect(
                    render, 0f, PDRectangle.A4.height - 10f, PDRectangle.A4.width, 10f
                )
                assertTrue("expected ink inside placement", inside > 0)
                assertEquals("top strip must stay blank", 0, topStrip)
            } finally {
                render.recycle()
            }
        }
    }

    // A7 ---------------------------------------------------------------------------------------
    @Test
    fun a7_portraitAndLandscapeAndLetter_mediaBoxAndPlacement() {
        val file = newTemp("a7")
        val landscape = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) // == iText A4.rotate()
        val bmp = PocSupport.testBitmap(1200, 800, Color.rgb(220, 220, 255))
        val placements = mutableListOf<PocSupport.Placement>()
        try {
            PDDocument().use { doc ->
                val image = LosslessFactory.createFromImage(doc, bmp)
                placements.add(addImagePage(doc, PDRectangle.A4, image, 36f))
                placements.add(addImagePage(doc, landscape, image, 36f))
                placements.add(addImagePage(doc, PDRectangle.LETTER, image, 36f))
                doc.save(file)
            }
        } finally {
            bmp.recycle()
        }
        PDDocument.load(file).use { doc ->
            assertEquals(3, doc.numberOfPages)
            val expected = listOf(PDRectangle.A4, landscape, PDRectangle.LETTER)
            for (i in 0 until 3) {
                val box = doc.getPage(i).mediaBox
                assertEquals("page $i w", expected[i].width, box.width, 0.5f)
                assertEquals("page $i h", expected[i].height, box.height, 0.5f)
                val p = placements[i]
                assertTrue("page $i inside w", p.x >= 0f && p.x + p.w <= box.width + 0.01f)
                assertTrue("page $i inside h", p.y >= 0f && p.y + p.h <= box.height + 0.01f)
            }
            assertTrue("landscape page is wider than tall", doc.getPage(1).mediaBox.width > doc.getPage(1).mediaBox.height)
        }
    }

    // A8 ---------------------------------------------------------------------------------------
    /**
     * A8 - stream-level SAF compatibility ONLY. A real content:// provider is not available inside a
     * :core instrumentation test, so this proves the PDFBox side works with arbitrary
     * InputStream/OutputStream (which is all a SAF URI gives you). It does NOT prove behaviour against
     * a live DocumentsProvider. No File(uri.path) is used anywhere.
     */
    @Test
    fun a8_saveToArbitraryOutputStream_andLoadFromInputStream() {
        val memory = ByteArrayOutputStream()
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.addPage(PDPage(PDRectangle.LETTER))
            doc.save(memory) // stands in for contentResolver.openOutputStream(uri)
        }
        val bytes = memory.toByteArray()
        assertTrue(bytes.size > 100)
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc -> // stands in for openInputStream(uri)
            assertEquals(2, doc.numberOfPages)
        }
        // temp-file-then-copy pattern used elsewhere in the project
        val tmp = newTemp("a8")
        FileOutputStream(tmp).use { it.write(bytes) }
        PDDocument.load(tmp).use { doc -> assertEquals(2, doc.numberOfPages) }
    }
}
