package com.propdf.annotations

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.model.HighlightAnnotation
import com.propdf.annotations.persistence.AnnotationDatabase
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.corpus.CorpusAssets
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 1: flattened overlay annotations must land where the user drew them on rotated pages, on pages with a
 * non-zero CropBox origin, and on pages of different sizes. Each case flattens a highlight drawn at a known
 * display rectangle, renders the result with PdfRenderer at 1 px per point, and checks pixels. Instrumented;
 * NOT RUN in the authoring environment (no SDK/device).
 */
@RunWith(AndroidJUnit4::class)
class AnnotationFlattenGeometryInstrumentedTest {

    private lateinit var db: AnnotationDatabase
    private lateinit var repo: AnnotationRepository
    private lateinit var exporter: PdfAnnotationExporter
    private lateinit var dir: File

    @Before fun setUp() {
        val ctx = CorpusAssets.targetContext
        PDFBoxResourceLoader.init(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AnnotationDatabase::class.java).allowMainThreadQueries().build()
        repo = AnnotationRepository(db.annotationDao())
        exporter = PdfAnnotationExporter(ctx, repo)
        dir = CorpusAssets.workDir("flatten_geometry")
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun makePdf(name: String, w: Float, h: Float, rotation: Int, crop: PDRectangle?): File {
        val f = File(dir, name)
        PDDocument().use { d ->
            val p = PDPage(PDRectangle(w, h)); p.rotation = rotation; if (crop != null) p.cropBox = crop
            d.addPage(p); d.save(f)
        }
        return f
    }

    private fun renderFirstPage(pdf: File): Bitmap {
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        } finally { fd.close() }
    }

    private fun isYellow(c: Int) = Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) < 120
    private fun isWhite(c: Int) = Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240

    /** Draws a highlight at display rect (50,60)-(150,100) and checks it lands there and nowhere else. */
    private fun check(name: String, w: Float, h: Float, rotation: Int, crop: PDRectangle?) = runBlocking<Unit> {
        val input = makePdf("$name.pdf", w, h, rotation, crop)
        val out = File(dir, "$name-out.pdf")
        val docId = "geo-$name"
        repo.saveAnnotation(
            docId, input.path,
            HighlightAnnotation(
                pageIndex = 0, highlightType = HighlightAnnotation.HighlightType.HIGHLIGHT,
                rects = listOf(RectF(50f, 60f, 150f, 100f)), color = Color.YELLOW
            )
        )
        val r = exporter.flattenAnnotations(input, out, docId)
        assertTrue("$name: ${r.exceptionOrNull()}", r.isSuccess)
        val bmp = renderFirstPage(out)
        try {
            assertTrue("$name: inside rect should be yellow, was ${Integer.toHexString(bmp.getPixel(100, 80))}", isYellow(bmp.getPixel(100, 80)))
            assertTrue("$name: just left of rect should be white", isWhite(bmp.getPixel(40, 80)))
            assertTrue("$name: just below rect should be white", isWhite(bmp.getPixel(100, 115)))
            assertTrue("$name: far corner should be white", isWhite(bmp.getPixel(bmp.width - 10, bmp.height - 10)))
        } finally { bmp.recycle() }
    }

    @Test fun rotation0() = check("r0", 400f, 600f, 0, null)
    @Test fun rotation90() = check("r90", 400f, 600f, 90, null)
    @Test fun rotation180() = check("r180", 400f, 600f, 180, null)
    @Test fun rotation270() = check("r270", 400f, 600f, 270, null)
    @Test fun nonZeroCropBoxOrigin() = check("crop", 400f, 600f, 0, PDRectangle(30f, 40f, 300f, 500f))
    @Test fun nonZeroCropBoxOrigin_rotated90() = check("crop90", 400f, 600f, 90, PDRectangle(30f, 40f, 300f, 500f))
    @Test fun differentPageSize_a5ish() = check("small", 300f, 420f, 0, null)
    @Test fun differentPageSize_landscape() = check("land", 600f, 400f, 0, null)

    @Test fun twoAnnotationsOnOnePage_bothLand() = runBlocking<Unit> {
        val input = makePdf("two.pdf", 400f, 600f, 90, null)
        val out = File(dir, "two-out.pdf")
        listOf(RectF(50f, 60f, 150f, 100f), RectF(200f, 300f, 260f, 340f)).forEach {
            repo.saveAnnotation("geo-two", input.path, HighlightAnnotation(
                pageIndex = 0, highlightType = HighlightAnnotation.HighlightType.HIGHLIGHT, rects = listOf(it), color = Color.YELLOW))
        }
        assertTrue(exporter.flattenAnnotations(input, out, "geo-two").isSuccess)
        val bmp = renderFirstPage(out)
        try {
            assertEquals(600, bmp.width); assertEquals(400, bmp.height)
            assertTrue(isYellow(bmp.getPixel(100, 80))); assertTrue(isYellow(bmp.getPixel(230, 320)))
        } finally { bmp.recycle() }
    }
}
