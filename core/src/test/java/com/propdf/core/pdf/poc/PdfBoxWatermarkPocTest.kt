package com.propdfeditor.core.pdf.poc

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * PHASE 4A / POC B - text watermark with PDFBox Android 2.0.27.0.
 * ISOLATED: does not touch WatermarkWorker / WatermarkEngine. iText stays in place.
 *
 * SIGNATURE CAVEAT (documented, not solved here): adding a watermark to an already digitally signed
 * PDF changes the document bytes/content and normally invalidates the existing signature. That is
 * inherent to PDF signing (in iText and in PDFBox alike) and is a product/security decision.
 *
 * SOURCE-READING FINDING (from WatermarkWorker.applyTextWatermark, not exercised by these tests):
 * production passes config.rotation (default 45f, documented as degrees) to iText
 * Canvas.showTextAligned(..., radAngle). iText's parameter is RADIANS, so production most likely
 * renders ~58 degrees, not 45. The PoC below takes degrees and converts explicitly.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxWatermarkPocTest {

    companion object {
        private const val WM = "CONFIDENTIAL"

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

    // ---- fixture: source PDF with per-page text + one image per page -----------------------------
    private fun pageMarker(n: Int) = "ORIGINAL-PAGE-$n"

    private fun buildSource(file: File, sizes: List<PDRectangle>) {
        PDDocument().use { doc ->
            val bmp = PocSupport.testBitmap(200, 100, Color.rgb(200, 255, 200))
            try {
                val image = LosslessFactory.createFromImage(doc, bmp)
                sizes.forEachIndexed { i, size ->
                    val page = PDPage(size)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 14f)
                        cs.newLineAtOffset(40f, size.height - 60f)
                        cs.showText(pageMarker(i + 1))
                        cs.endText()
                        cs.drawImage(image, 40f, size.height - 200f, 200f, 100f)
                    }
                }
            } finally {
                bmp.recycle()
            }
            doc.save(file)
        }
    }

    // ---- PoC watermark implementation (would become production code in a later phase) -----------
    data class TextWatermark(
        val text: String,
        val fontSize: Float = 60f,
        val opacity: Float = 0.5f,
        val angleDegrees: Float = 0f,
        val gray: Int = 128,
        val tiled: Boolean = false,
        val tileCols: Int = 2,
        val tileRows: Int = 3
    )

    private fun textWidth(text: String, size: Float): Float =
        PDType1Font.HELVETICA_BOLD.getStringWidth(text) / 1000f * size

    /** Bottom-left start point that puts the text box centre at (cx, cy) once rotated by [rad]. */
    private fun centredOrigin(cx: Float, cy: Float, w: Float, h: Float, rad: Double): Pair<Float, Float> {
        val x0 = cx - (w / 2f) * cos(rad).toFloat() + (h / 2f) * sin(rad).toFloat()
        val y0 = cy - (w / 2f) * sin(rad).toFloat() - (h / 2f) * cos(rad).toFloat()
        return x0 to y0
    }

    /** The four corners of the rotated text box, for the analytical in-bounds check. */
    private fun corners(x0: Float, y0: Float, w: Float, h: Float, rad: Double): List<Pair<Float, Float>> {
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun p(dx: Float, dy: Float) = (x0 + dx * c - dy * s) to (y0 + dx * s + dy * c)
        return listOf(p(0f, 0f), p(w, 0f), p(w, h), p(0f, h))
    }

    private fun applyTextWatermark(doc: PDDocument, page: PDPage, wm: TextWatermark) {
        val box = page.mediaBox
        val rad = Math.toRadians(wm.angleDegrees.toDouble())
        val w = textWidth(wm.text, wm.fontSize)
        val h = wm.fontSize * 0.7f // approximate cap height
        val gs = PDExtendedGraphicsState()
        gs.setNonStrokingAlphaConstant(wm.opacity)
        gs.setStrokingAlphaConstant(wm.opacity)

        // AppendMode.APPEND + compress + resetContext=true: keeps the existing content stream and
        // wraps it in q/Q so the watermark cannot inherit stray graphics state from it.
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(wm.gray, wm.gray, wm.gray)
            val anchors = ArrayList<Pair<Float, Float>>()
            if (wm.tiled) {
                for (r in 0 until wm.tileRows) for (c in 0 until wm.tileCols) {
                    val cx = box.width * (c + 0.5f) / wm.tileCols
                    val cy = box.height * (r + 0.5f) / wm.tileRows
                    anchors.add(centredOrigin(cx, cy, w, h, rad))
                }
            } else {
                anchors.add(centredOrigin(box.width / 2f, box.height / 2f, w, h, rad))
            }
            for ((x0, y0) in anchors) {
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, wm.fontSize)
                cs.setTextMatrix(Matrix.getRotateInstance(rad, x0, y0))
                cs.showText(wm.text)
                cs.endText()
            }
        }
    }

    private fun watermarkFile(src: File, dst: File, wm: TextWatermark) {
        PDDocument.load(src).use { doc ->
            for (i in 0 until doc.numberOfPages) applyTextWatermark(doc, doc.getPage(i), wm)
            doc.save(dst)
        }
    }

    private fun alphaValues(page: PDPage): List<Float> {
        val res = page.resources ?: return emptyList()
        val out = ArrayList<Float>()
        for (name in res.extGStateNames) {
            val g = res.getExtGState(name) ?: continue
            g.nonStrokingAlphaConstant?.let { out.add(it) }
        }
        return out
    }

    // B1 ---------------------------------------------------------------------------------------
    @Test
    fun b1_textWatermark_keepsOriginal_addsWatermark_reopens() {
        val src = newTemp("b1src")
        val dst = newTemp("b1dst")
        buildSource(src, listOf(PDRectangle.A4))
        watermarkFile(src, dst, TextWatermark(WM))
        PDDocument.load(dst).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val text = PocSupport.pageText(doc, 1)
            assertTrue("original text lost: $text", text.contains(pageMarker(1)))
            assertTrue("watermark text missing: $text", text.contains(WM))
            assertEquals("original image lost", 1, PocSupport.imageCount(doc.getPage(0)))
        }
    }

    // B2 ---------------------------------------------------------------------------------------
    @Test
    fun b2_centered_onA4Portrait_A4Landscape_andLetter() {
        val sizes = listOf(
            PDRectangle.A4,
            PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width),
            PDRectangle.LETTER
        )
        val src = newTemp("b2src")
        val dst = newTemp("b2dst")
        buildSource(src, sizes)
        watermarkFile(src, dst, TextWatermark(WM, fontSize = 60f, opacity = 1f))
        PDDocument.load(src).use { before ->
            PDDocument.load(dst).use { after ->
                for (i in sizes.indices) {
                    val box = after.getPage(i).mediaBox
                    val b = PocSupport.render(before, i)
                    val a = PocSupport.render(after, i)
                    try {
                        val cx = box.width / 2f
                        val cy = box.height / 2f
                        val w = textWidth(WM, 60f)
                        val inkBefore = PocSupport.inkInPdfRect(b, cx - w / 2f, cy - 30f, w, 60f)
                        val inkAfter = PocSupport.inkInPdfRect(a, cx - w / 2f, cy - 30f, w, 60f)
                        assertTrue("page $i: nothing drawn at centre ($inkBefore -> $inkAfter)", inkAfter > inkBefore + 100)
                    } finally {
                        b.recycle(); a.recycle()
                    }
                }
            }
        }
    }

    // B3 ---------------------------------------------------------------------------------------
    @Test
    fun b3_diagonal45_staysInsidePage_originalIntact() {
        val sizes = listOf(
            PDRectangle.A4,
            PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width),
            PDRectangle.LETTER,
            PDRectangle(500f, 500f)
        )
        val wm = TextWatermark(WM, fontSize = 60f, opacity = 0.5f, angleDegrees = 45f)
        val src = newTemp("b3src")
        val dst = newTemp("b3dst")
        buildSource(src, sizes)
        watermarkFile(src, dst, wm)

        // Analytical in-bounds check of the rotated text box (independent of any renderer).
        val w = textWidth(WM, wm.fontSize)
        val h = wm.fontSize * 0.7f
        val rad = Math.toRadians(45.0)
        for ((i, size) in sizes.withIndex()) {
            val (x0, y0) = centredOrigin(size.width / 2f, size.height / 2f, w, h, rad)
            for ((cx, cy) in corners(x0, y0, w, h, rad)) {
                assertTrue("page $i corner x=$cx", cx >= 0f && cx <= size.width)
                assertTrue("page $i corner y=$cy", cy >= 0f && cy <= size.height)
            }
        }
        PDDocument.load(dst).use { doc ->
            assertEquals(sizes.size, doc.numberOfPages)
            for (i in sizes.indices) {
                val text = PocSupport.pageText(doc, i + 1)
                assertTrue("page $i original", text.contains(pageMarker(i + 1)))
                assertTrue("page $i watermark", text.contains(WM))
            }
        }
    }

    // B4 ---------------------------------------------------------------------------------------
    @Test
    fun b4_opacity_isWrittenAsRealGraphicsState_andAffectsRendering() {
        val levels = listOf(1.0f, 0.5f, 0.25f)
        val src = newTemp("b4src")
        buildSource(src, listOf(PDRectangle.A4))
        val darkness = ArrayList<Long>()
        for (level in levels) {
            val dst = newTemp("b4_${(level * 100).toInt()}")
            watermarkFile(src, dst, TextWatermark(WM, fontSize = 80f, opacity = level, gray = 0))
            PDDocument.load(dst).use { doc ->
                val alphas = alphaValues(doc.getPage(0))
                assertTrue("no ExtGState alpha found for $level: $alphas", alphas.any { abs(it - level) < 0.001f })
                val render = PocSupport.render(doc, 0)
                try {
                    darkness.add(PocSupport.darkness(render))
                } finally {
                    render.recycle()
                }
            }
        }
        // Real transparency => less ink at lower opacity. If the renderer ignored alpha this fails.
        assertTrue("100% should be darker than 50%: $darkness", darkness[0] > darkness[1])
        assertTrue("50% should be darker than 25%: $darkness", darkness[1] > darkness[2])
    }

    // B5 ---------------------------------------------------------------------------------------
    @Test
    fun b5_multiPage_everyPageWatermarked_andOriginalKept() {
        val src = newTemp("b5src")
        val dst = newTemp("b5dst")
        buildSource(src, listOf(PDRectangle.A4, PDRectangle.A4, PDRectangle.A4))
        watermarkFile(src, dst, TextWatermark(WM))
        PDDocument.load(dst).use { doc ->
            assertEquals(3, doc.numberOfPages)
            for (n in 1..3) {
                val text = PocSupport.pageText(doc, n)
                assertTrue("page $n original", text.contains(pageMarker(n)))
                assertTrue("page $n watermark", text.contains(WM))
                assertEquals("page $n image", 1, PocSupport.imageCount(doc.getPage(n - 1)))
            }
        }
    }

    // B5b: repeated (tiled) watermark ------------------------------------------------------------
    @Test
    fun b5b_repeatedWatermark_tiledUnrotated_countMatchesGrid() {
        val wm = TextWatermark(WM, fontSize = 30f, opacity = 0.5f, tiled = true, tileCols = 2, tileRows = 3)
        val src = newTemp("b5bsrc")
        val dst = newTemp("b5bdst")
        buildSource(src, listOf(PDRectangle.A4))
        watermarkFile(src, dst, wm)
        PDDocument.load(dst).use { doc ->
            val text = PocSupport.pageText(doc, 1)
            val count = Regex(WM).findAll(text).count()
            assertEquals("expected ${wm.tileCols * wm.tileRows} tiles in: $text", wm.tileCols * wm.tileRows, count)
        }
    }

    // B6 ---------------------------------------------------------------------------------------
    @Test
    fun b6_differentPageSizes_mediaBoxUnchanged_andWatermarkOnEach() {
        val sizes = listOf(
            PDRectangle.A4,
            PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width),
            PDRectangle.LETTER,
            PDRectangle(400f, 400f)
        )
        val src = newTemp("b6src")
        val dst = newTemp("b6dst")
        buildSource(src, sizes)
        watermarkFile(src, dst, TextWatermark(WM, fontSize = 40f))
        PDDocument.load(dst).use { doc ->
            assertEquals(sizes.size, doc.numberOfPages)
            for (i in sizes.indices) {
                val box = doc.getPage(i).mediaBox
                assertEquals("page $i w", sizes[i].width, box.width, 0.5f)
                assertEquals("page $i h", sizes[i].height, box.height, 0.5f)
                assertTrue("page $i watermark", PocSupport.pageText(doc, i + 1).contains(WM))
            }
        }
    }

    // B7 ---------------------------------------------------------------------------------------
    @Test
    fun b7_existingPdfPreservation_textImagePagesResources() {
        val src = newTemp("b7src")
        val dst = newTemp("b7dst")
        buildSource(src, listOf(PDRectangle.A4, PDRectangle.A4, PDRectangle.A4))
        val imageCountsBefore = ArrayList<Int>()
        val imageSizeBefore = ArrayList<Pair<Int, Int>>()
        PDDocument.load(src).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                imageCountsBefore.add(PocSupport.imageCount(doc.getPage(i)))
                val img = PocSupport.firstImage(doc.getPage(i))!!
                imageSizeBefore.add(img.width to img.height)
            }
        }
        watermarkFile(src, dst, TextWatermark(WM, angleDegrees = 45f))
        PDDocument.load(dst).use { doc ->
            assertEquals("page count changed", 3, doc.numberOfPages)
            for (i in 0 until 3) {
                assertEquals("page $i image count", imageCountsBefore[i], PocSupport.imageCount(doc.getPage(i)))
                val img = PocSupport.firstImage(doc.getPage(i))!!
                assertEquals("page $i image size", imageSizeBefore[i], img.width to img.height)
                assertTrue("page $i original text", PocSupport.pageText(doc, i + 1).contains(pageMarker(i + 1)))
            }
            // A second load/save cycle must also succeed (no dangling references after incremental edits).
            val again = newTemp("b7again")
            doc.save(again)
            PDDocument.load(again).use { d2 -> assertEquals(3, d2.numberOfPages) }
        }
    }
}
