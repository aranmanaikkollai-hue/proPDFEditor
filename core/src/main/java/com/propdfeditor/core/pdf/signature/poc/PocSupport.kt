package com.propdfeditor.core.pdf.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * PHASE 4A - isolated PoC support code. NOT production code. Nothing in the app depends on it.
 * Lives in core/src/androidTest so it is only compiled into the instrumentation APK.
 *
 * PDFBox Android needs an Android Context (PDFBoxResourceLoader.init) for Standard-14 font metrics,
 * glyph lists and colour profiles, and the tests use android.graphics.Bitmap, so these must be
 * instrumented (androidTest) tests, not plain JVM tests. The app process initialises PDFBox through
 * app/.../startup/PDFBoxInitializer; a :core instrumentation run is a separate process, so the PoC
 * has to initialise it itself (once, in @BeforeClass).
 */
object PocSupport {

    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    fun initPdfBox() {
        PDFBoxResourceLoader.init(context())
    }

    fun tempPdf(tag: String): File = File.createTempFile("poc_$tag", ".pdf", context().cacheDir)

    /** Opaque bitmap: solid [fill] with a contrasting block so the image is never uniformly blank. */
    fun testBitmap(width: Int, height: Int, fill: Int, block: Int = Color.BLACK): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(fill)
        val paint = Paint().apply { color = block }
        canvas.drawRect(width * 0.1f, height * 0.1f, width * 0.5f, height * 0.5f, paint)
        return bmp
    }

    fun pngBytes(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun jpegBytes(bmp: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    data class Placement(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Deterministic fit-inside algorithm used by the creation PoC:
     *   scale = min((pageW - 2m) / imgW, (pageH - 2m) / imgH)     (never distorts: one uniform scale)
     *   w = imgW * scale, h = imgH * scale
     *   image is centred inside the margin box (PDF origin = bottom-left).
     * NOTE: production PdfCreator relies on iText Image.setAutoScale(true) inside Document flow
     * (default 36pt margins, top-left anchored). This PoC centres instead - a deliberate difference
     * that a real migration would have to decide on.
     */
    fun fitInside(imgW: Int, imgH: Int, pageW: Float, pageH: Float, margin: Float = 0f): Placement {
        val availW = pageW - 2f * margin
        val availH = pageH - 2f * margin
        val scale = min(availW / imgW, availH / imgH)
        val w = imgW * scale
        val h = imgH * scale
        return Placement(margin + (availW - w) / 2f, margin + (availH - h) / 2f, w, h)
    }

    /** Number of /Image XObjects directly in the page's resources. */
    fun imageCount(page: PDPage): Int {
        val res = page.resources ?: return 0
        var n = 0
        for (name in res.xObjectNames) {
            if (res.getXObject(name) is PDImageXObject) n++
        }
        return n
    }

    fun firstImage(page: PDPage): PDImageXObject? {
        val res = page.resources ?: return null
        for (name in res.xObjectNames) {
            val x = res.getXObject(name)
            if (x is PDImageXObject) return x
        }
        return null
    }

    fun pageText(doc: PDDocument, pageNumber1Based: Int): String {
        val s = PDFTextStripper()
        s.startPage = pageNumber1Based
        s.endPage = pageNumber1Based
        return s.getText(doc)
    }

    /** Renders page (0-based) at 72 dpi (1 px = 1 pt). Caller must recycle the bitmap. */
    fun render(doc: PDDocument, pageIndex: Int = 0): Bitmap = PDFRenderer(doc).renderImage(pageIndex, 1f)

    private fun isInk(pixel: Int): Boolean = (pixel and 0x00FFFFFF) != 0x00FFFFFF

    /** Count of non-white pixels inside a PDF-space rectangle (bottom-left origin) of a 72dpi render. */
    fun inkInPdfRect(bmp: Bitmap, x: Float, y: Float, w: Float, h: Float): Int {
        val left = x.toInt().coerceIn(0, bmp.width)
        val right = (x + w).toInt().coerceIn(0, bmp.width)
        val top = (bmp.height - (y + h)).toInt().coerceIn(0, bmp.height)
        val bottom = (bmp.height - y).toInt().coerceIn(0, bmp.height)
        var count = 0
        for (py in top until bottom) for (px in left until right) if (isInk(bmp.getPixel(px, py))) count++
        return count
    }

    /** Sum of (255 - luminance) over the whole bitmap: a monotonic "how much ink" measure. */
    fun darkness(bmp: Bitmap): Long {
        var sum = 0L
        for (py in 0 until bmp.height) for (px in 0 until bmp.width) {
            val p = bmp.getPixel(px, py)
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            sum += (255 - lum)
        }
        return sum
    }

    /** Pixels that differ between two same-size renders. */
    fun diffPixels(a: Bitmap, b: Bitmap): Int {
        require(a.width == b.width && a.height == b.height) { "render sizes differ" }
        var count = 0
        for (py in 0 until a.height) for (px in 0 until a.width) if (a.getPixel(px, py) != b.getPixel(px, py)) count++
        return count
    }
}
