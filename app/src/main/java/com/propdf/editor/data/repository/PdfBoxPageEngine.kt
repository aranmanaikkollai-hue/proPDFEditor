package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.propdf.core.domain.model.BackgroundConfig
import com.propdf.core.domain.model.HeaderFooterConfig
import com.propdf.core.domain.model.ImageInsertionConfig
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.core.domain.model.WatermarkPosition
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.PdfProcessingError
import com.propdfeditor.batch.util.PdfOperationException
import com.propdfeditor.batch.util.PdfPasswordException
import com.propdfeditor.batch.util.SignedPdfException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Canonical PDFBox (com.tom-roush:pdfbox-android:2.0.27.0) engine for the Page Editor path.
 *
 * Contract shared by every operation:
 *  - input and output are local Files (Uri/SAF resolution lives in the repository);
 *  - the input is never modified;
 *  - output is written to a sibling temp file and renamed only after a successful save
 *    ([publish]); on any failure or cancellation the temp file is deleted and nothing is published;
 *  - every PDDocument is closed in a finally block;
 *  - password-protected and signed documents are rejected, never silently altered.
 *
 * Page-assembly strategy:
 *  - operations that keep every page (reorder, duplicate, insert, rotate, crop, resize, mirror,
 *    watermark, numbering, headers, background) edit the document in place so forms, outlines and
 *    annotations survive;
 *  - operations that DROP pages (delete, extract, split) build a fresh document with
 *    PDDocument.importPage so removed pages' content cannot linger in the output.
 *    Interactive form fields and bookmarks are not carried across those operations.
 */
internal class PdfBoxPageEngine(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    // ------------------------------------------------------------------ plumbing

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

    private fun open(file: File): PDDocument {
        if (!file.exists() || file.length() == 0L) {
            throw AppException.FileNotFound("PDF file is missing or empty")
        }
        val doc = try {
            PDDocument.load(file, "", memory())
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException("This PDF is password protected. Remove the password before editing it.", e)
        } catch (e: IOException) {
            throw PdfProcessingError.CorruptedFile("PDF could not be opened", e)
        }
        return doc
    }

    /** Opens, applies the standard safety gates, runs [block], always closes. */
    private suspend fun <T> withDoc(file: File, block: suspend (PDDocument) -> T): T {
        val doc = open(file)
        try {
            rejectEncrypted(doc)
            rejectSigned(doc)
            if (doc.numberOfPages < 1) throw PdfProcessingError.CorruptedFile("PDF has no pages")
            return block(doc)
        } finally {
            closeQuietly(doc)
        }
    }

    private fun rejectEncrypted(doc: PDDocument) {
        if (doc.isEncrypted) {
            throw PdfPasswordException("This PDF is password protected. Remove the password before editing it.")
        }
    }

    private fun isSigned(doc: PDDocument): Boolean {
        if (doc.signatureDictionaries.isNotEmpty()) return true
        val perms = doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
        return perms != null && perms.containsKey(COSName.getPDFName("DocMDP"))
    }

    private fun rejectSigned(doc: PDDocument) {
        if (isSigned(doc)) throw SignedPdfException()
    }

    private fun closeQuietly(doc: PDDocument) {
        try { doc.close() } catch (_: Exception) { }
    }

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()

    /**
     * Runs [write] against a temp sibling of [output]; renames over [output] only when it
     * completed and the coroutine is still active. Deletes the temp file otherwise.
     */
    private suspend fun publish(output: File, write: suspend (File) -> Unit): File {
        val dir = output.absoluteFile.parentFile ?: context.cacheDir
        val tmp = File(dir, ".${output.name}.${System.nanoTime()}.tmp")
        try {
            write(tmp)
            checkActive()
            if (!tmp.exists() || tmp.length() == 0L) throw IOException("Output was not written")
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
            return output
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun saveTo(doc: PDDocument, tmp: File) {
        tmp.outputStream().buffered().use { doc.save(it) }
    }

    fun pageCount(input: File): Int {
        val doc = open(input)
        try {
            return doc.numberOfPages
        } finally {
            closeQuietly(doc)
        }
    }

    private fun geometry(page: PDPage): PageGeometry {
        val box = page.cropBox
        return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, page.rotation)
    }

    /** Re-parenting a page loses inherited MediaBox/CropBox/Rotate/Resources; copy them onto the page itself. */
    private fun materializeInherited(page: PDPage) {
        val media = page.mediaBox
        val crop = page.cropBox
        val rotation = page.rotation
        val resources = page.resources
        page.mediaBox = media
        page.cropBox = crop
        page.rotation = rotation
        if (resources != null) page.resources = resources
    }

    private fun targetsOf(pages: Collection<Int>, count: Int): List<Int> =
        if (pages.isEmpty()) (1..count).toList() else pages.filter { it in 1..count }.distinct()

    // ------------------------------------------------------------------ assembly

    /**
     * Produces a document whose pages are [order] (1-based, repeats allowed) of [input].
     * Every output position holds exactly one page object; asserted before saving.
     */
    suspend fun assemble(input: File, output: File, order: List<Int>): File {
        if (order.isEmpty()) throw PdfProcessingError.InvalidPage("No pages selected")
        return withDoc(input) { src ->
            val n = src.numberOfPages
            if (order.any { it !in 1..n }) throw PdfProcessingError.InvalidPage("Page out of range")
            publish(output) { tmp ->
                if (PageOrderPlanner.coversAllPages(n, order)) {
                    rebuildInPlace(src, order)
                    checkOutput(src, order.size)
                    saveTo(src, tmp)
                } else {
                    importIntoNew(src, order, tmp)
                }
            }
        }
    }

    /** PDDocumentCatalog has no setPages(); point /Pages at a fresh, empty page tree instead. */
    private fun resetPageTree(doc: PDDocument) {
        doc.documentCatalog.cosObject.setItem(COSName.PAGES, PDPageTree().cosObject)
    }

    /** Reorder/duplicate without dropping anything: edit the same document, keep forms/outlines. */
    private suspend fun rebuildInPlace(doc: PDDocument, order: List<Int>) {
        val n = doc.numberOfPages
        val originals = (0 until n).map { doc.getPage(it) }
        originals.forEach { materializeInherited(it) }

        val used = BooleanArray(n)
        val ordered = ArrayList<PDPage>(order.size)
        for (idx in order) {
            checkActive()
            val original = originals[idx - 1]
            if (!used[idx - 1]) {
                used[idx - 1] = true
                ordered.add(original)
            } else {
                // importPage appends a fresh page object; it is re-positioned below.
                ordered.add(doc.importPage(original))
            }
        }
        resetPageTree(doc)
        for (p in ordered) doc.addPage(p)
    }

    /** Builds a new document from [order]; dropped pages cannot survive in the output. */
    private suspend fun importIntoNew(src: PDDocument, order: List<Int>, tmp: File) {
        val dest = PDDocument(memory())
        try {
            for (idx in order) {
                checkActive()
                importOne(dest, src, idx)
            }
            checkOutput(dest, order.size)
            saveTo(dest, tmp)
        } finally {
            closeQuietly(dest)
        }
    }

    private fun importOne(dest: PDDocument, src: PDDocument, page1: Int) {
        val imported = dest.importPage(src.getPage(page1 - 1))
        try {
            // Annotation /P would otherwise drag the whole source page tree into the output.
            imported.annotations.forEach { it.page = null }
        } catch (_: Exception) { }
    }

    private fun checkOutput(doc: PDDocument, expectedPages: Int) {
        val seen = IdentityHashMap<Any, Boolean>()
        for (i in 0 until doc.numberOfPages) seen[doc.getPage(i).cosObject] = true
        if (doc.numberOfPages != expectedPages || seen.size != expectedPages) {
            throw PdfProcessingError.ProcessingFailed(
                "Page assembly mismatch: expected $expectedPages distinct pages, got ${doc.numberOfPages}/${seen.size}"
            )
        }
    }

    suspend fun merge(inputs: List<File>, output: File): File {
        if (inputs.isEmpty()) throw PdfProcessingError.InvalidPage("Nothing to merge")
        return publish(output) { tmp ->
            val opened = ArrayList<PDDocument>()
            val dest = PDDocument(memory())
            try {
                var expected = 0
                for (f in inputs) {
                    checkActive()
                    val d = open(f)
                    opened.add(d)
                    rejectEncrypted(d)
                    rejectSigned(d)
                    expected += d.numberOfPages
                }
                val merger = PDFMergerUtility()
                for (d in opened) {
                    checkActive()
                    merger.appendDocument(dest, d)
                }
                if (dest.numberOfPages != expected) {
                    throw PdfProcessingError.ProcessingFailed(
                        "Merge mismatch: expected $expected pages, got ${dest.numberOfPages}"
                    )
                }
                saveTo(dest, tmp)
            } finally {
                closeQuietly(dest)
                opened.forEach { closeQuietly(it) }
            }
        }
    }

    /** Writes one file per range (1-based inclusive, clamped); [outputFor] names each part. */
    suspend fun splitRanges(input: File, ranges: List<IntRange>, outputFor: (Int) -> File): List<File> {
        if (ranges.isEmpty()) throw PdfProcessingError.InvalidPage("No ranges")
        val produced = ArrayList<File>()
        try {
            withDoc(input) { src ->
                val n = src.numberOfPages
                ranges.forEachIndexed { i, r ->
                    checkActive()
                    val first = r.first.coerceIn(1, n)
                    val last = r.last.coerceIn(1, n)
                    val order = if (first <= last) (first..last).toList() else (last..first).toList()
                    val out = publish(outputFor(i)) { tmp -> importIntoNew(src, order, tmp) }
                    produced.add(out)
                }
            }
        } catch (t: Throwable) {
            // A split is all-or-nothing: do not leave earlier parts behind.
            produced.forEach { it.delete() }
            throw t
        }
        return produced
    }

    /** Top-level bookmark count (used by split-by-bookmark's existing chunking heuristic). */
    fun topLevelBookmarkCount(input: File): Int {
        val doc = open(input)
        try {
            val outline = doc.documentCatalog.documentOutline ?: return 0
            var count = 0
            var child = outline.firstChild
            while (child != null) { count++; child = child.nextSibling }
            return count
        } catch (_: Exception) {
            return 0
        } finally {
            closeQuietly(doc)
        }
    }

    suspend fun insertPdf(main: File, toInsert: File, position: Int, sourcePages: List<Int>, output: File): File =
        withDoc(main) { doc ->
            val ins = open(toInsert)
            try {
                rejectEncrypted(ins)
                rejectSigned(ins)
                val chosen = sourcePages.ifEmpty { (1..ins.numberOfPages).toList() }
                    .filter { it in 1..ins.numberOfPages }
                if (chosen.isEmpty()) throw PdfProcessingError.InvalidPage("No pages to insert")
                val n = doc.numberOfPages
                val originals = (0 until n).map { doc.getPage(it) }
                originals.forEach { materializeInherited(it) }
                val imported = chosen.map { checkActive(); doc.importPage(ins.getPage(it - 1)) }
                val ordered = PageOrderPlanner.insertAt(originals, position, imported)
                resetPageTree(doc)
                ordered.forEach { doc.addPage(it) }
                checkOutput(doc, n + chosen.size)
                publish(output) { tmp -> saveTo(doc, tmp) }
            } finally {
                closeQuietly(ins)
            }
        }

    suspend fun insertBlank(input: File, output: File, position: Int, width: Float, height: Float): File =
        withDoc(input) { doc ->
            val n = doc.numberOfPages
            val originals = (0 until n).map { doc.getPage(it) }
            originals.forEach { materializeInherited(it) }
            val blank = PDPage(PDRectangle(width.coerceAtLeast(1f), height.coerceAtLeast(1f)))
            val ordered = PageOrderPlanner.insertAt(originals, position, listOf(blank))
            resetPageTree(doc)
            ordered.forEach { doc.addPage(it) }
            checkOutput(doc, n + 1)
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    suspend fun insertImagePage(input: File, output: File, position: Int, config: ImageInsertionConfig): File =
        withDoc(input) { doc ->
            val n = doc.numberOfPages
            val originals = (0 until n).map { doc.getPage(it) }
            originals.forEach { materializeInherited(it) }
            val page = imagePage(doc, config.imageUri, config)
            val ordered = PageOrderPlanner.insertAt(originals, position, listOf(page))
            resetPageTree(doc)
            ordered.forEach { doc.addPage(it) }
            checkOutput(doc, n + 1)
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    /** One page per image; used by combineImagesToPdf (config page size) and imagesToPdf (native size). */
    suspend fun imagesToPdf(images: List<Uri>, output: File, config: ImageInsertionConfig?): File {
        if (images.isEmpty()) throw PdfProcessingError.InvalidPage("No images")
        return publish(output) { tmp ->
            val doc = PDDocument(memory())
            try {
                for (uri in images) {
                    checkActive()
                    val page = if (config != null) imagePage(doc, uri, config) else nativeSizeImagePage(doc, uri)
                    doc.addPage(page)
                }
                checkOutput(doc, images.size)
                saveTo(doc, tmp)
            } finally {
                closeQuietly(doc)
            }
        }
    }

    suspend fun insertImageOnLastPage(input: File, output: File, config: ImageInsertionConfig): File =
        withDoc(input) { doc ->
            val page = doc.getPage(doc.numberOfPages - 1)
            val geo = geometry(page)
            val img = decodeImage(config.imageUri)
            try {
                val (w, h) = ImageLayout.fit(
                    config.fitMode, img.origWidth, img.origHeight,
                    geo.displayWidth - 2 * config.margin, geo.displayHeight - 2 * config.margin
                )
                val x = (geo.displayWidth - w) / 2f
                val y = (geo.displayHeight - h) / 2f
                val x0 = embed(doc, img.bitmap)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    drawImageDisplay(cs, geo, x0, x, y, w, h)
                }
            } finally {
                img.bitmap.recycle()
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    // ------------------------------------------------------------------ in-place page operations

    suspend fun rotate(input: File, output: File, rotations: Map<Int, Int>): File =
        withDoc(input) { doc ->
            for ((pageNum, delta) in rotations) {
                checkActive()
                if (pageNum !in 1..doc.numberOfPages) continue
                if (delta % 90 != 0) throw IllegalArgumentException("Rotation must be a multiple of 90 degrees")
                val page = doc.getPage(pageNum - 1)
                page.rotation = Math.floorMod(page.rotation + delta, 360)
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    suspend fun crop(
        input: File, output: File, pages: Collection<Int>,
        leftPt: Float, topPt: Float, rightPt: Float, bottomPt: Float
    ): File = withDoc(input) { doc ->
        for (n in targetsOf(pages, doc.numberOfPages)) {
            checkActive()
            val page = doc.getPage(n - 1)
            val geo = geometry(page)
            val m = geo.displayMarginsToUser(leftPt, topPt, rightPt, bottomPt)
            val l = geo.left + m.left
            val b = geo.bottom + m.bottom
            val w = (geo.boxWidth - m.left - m.right).coerceAtLeast(1f)
            val h = (geo.boxHeight - m.top - m.bottom).coerceAtLeast(1f)
            page.cropBox = PDRectangle(l, b, w, h)
        }
        publish(output) { tmp -> saveTo(doc, tmp) }
    }

    suspend fun resize(
        input: File, output: File, pages: Collection<Int>,
        targetW: Float, targetH: Float, keepAspect: Boolean, scaleContent: Boolean
    ): File = withDoc(input) { doc ->
        if (targetW <= 0f || targetH <= 0f) throw IllegalArgumentException("Invalid target size")
        for (n in targetsOf(pages, doc.numberOfPages)) {
            checkActive()
            val page = doc.getPage(n - 1)
            val geo = geometry(page)
            val plan = geo.planResize(targetW, targetH, keepAspect, scaleContent)
            wrapContent(doc, page, Matrix(plan.scaleX, 0f, 0f, plan.scaleY, plan.translateX, plan.translateY))
            moveAnnotations(page, plan.scaleX, plan.scaleY, plan.translateX, plan.translateY)
            val box = PDRectangle(0f, 0f, plan.newWidth, plan.newHeight)
            page.mediaBox = box
            page.cropBox = PDRectangle(0f, 0f, plan.newWidth, plan.newHeight)
        }
        publish(output) { tmp -> saveTo(doc, tmp) }
    }

    suspend fun mirror(input: File, output: File, pages: Collection<Int>, horizontal: Boolean): File =
        withDoc(input) { doc ->
            for (n in targetsOf(pages, doc.numberOfPages)) {
                checkActive()
                val page = doc.getPage(n - 1)
                val geo = geometry(page)
                val quarter = geo.rotation == 90 || geo.rotation == 270
                // A horizontal flip of the DISPLAYED page flips user-space Y on quarter-turn pages.
                val flipUserX = horizontal != quarter
                val m = if (flipUserX) {
                    Matrix(-1f, 0f, 0f, 1f, geo.left + geo.right, 0f)
                } else {
                    Matrix(1f, 0f, 0f, -1f, 0f, geo.bottom + geo.top)
                }
                wrapContent(doc, page, m)
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    /** q <cm> [existing content] Q, via a prepended and an appended content stream. */
    private fun wrapContent(doc: PDDocument, page: PDPage, m: Matrix) {
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true, false).use {
            it.saveGraphicsState()
            it.transform(m)
        }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, false).use {
            it.restoreGraphicsState()
        }
    }

    private fun moveAnnotations(page: PDPage, sx: Float, sy: Float, tx: Float, ty: Float) {
        try {
            for (a in page.annotations) {
                val r = a.rectangle ?: continue
                a.rectangle = PDRectangle(
                    r.lowerLeftX * sx + tx, r.lowerLeftY * sy + ty,
                    (r.upperRightX - r.lowerLeftX) * sx, (r.upperRightY - r.lowerLeftY) * sy
                )
            }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ drawing operations

    suspend fun watermark(input: File, output: File, config: WatermarkConfig): File =
        withDoc(input) { doc ->
            val font = PDType1Font.HELVETICA
            val text = printable(font, config.text)
            val wmImage = config.imageUri?.let { decodeImage(it) }
            try {
                val imageX = wmImage?.let { embed(doc, it.bitmap) }
                if (imageX == null && text.isBlank()) throw IllegalArgumentException("Watermark has no text or image")
                val gs = PDExtendedGraphicsState().apply {
                    setNonStrokingAlphaConstant(config.opacity.coerceIn(0f, 1f))
                    setStrokingAlphaConstant(config.opacity.coerceIn(0f, 1f))
                }
                for (n in targetsOf(config.pages, doc.numberOfPages)) {
                    checkActive()
                    val page = doc.getPage(n - 1)
                    val geo = geometry(page)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        val tw = if (imageX != null) 0f else font.getStringWidth(text) / 1000f * config.fontSize
                        val bw = if (imageX != null) geo.displayWidth * 0.5f else tw
                        val bh = if (imageX != null && wmImage != null) {
                            bw * wmImage.origHeight / wmImage.origWidth
                        } else config.fontSize
                        for ((cx, cy) in anchors(config.position, geo, bw, bh)) {
                            if (imageX != null) {
                                drawImageCentered(cs, geo, imageX, cx, cy, bw, bh, config.rotation)
                            } else {
                                cs.setNonStrokingColor(
                                    Color.red(config.color), Color.green(config.color), Color.blue(config.color)
                                )
                                drawTextCentered(cs, geo, font, text, config.fontSize, cx, cy, tw, config.rotation)
                            }
                        }
                    }
                }
            } finally {
                wmImage?.bitmap?.recycle()
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    /** Display-space centres for the watermark; TILE yields a bounded grid. */
    private fun anchors(pos: WatermarkPosition, geo: PageGeometry, bw: Float, bh: Float): List<Pair<Float, Float>> {
        val w = geo.displayWidth
        val h = geo.displayHeight
        val m = 36f
        return when (pos) {
            WatermarkPosition.CENTER -> listOf(w / 2f to h / 2f)
            WatermarkPosition.TOP_LEFT -> listOf((m + bw / 2f) to (h - m - bh / 2f))
            WatermarkPosition.TOP_RIGHT -> listOf((w - m - bw / 2f) to (h - m - bh / 2f))
            WatermarkPosition.BOTTOM_LEFT -> listOf((m + bw / 2f) to (m + bh / 2f))
            WatermarkPosition.BOTTOM_RIGHT -> listOf((w - m - bw / 2f) to (m + bh / 2f))
            WatermarkPosition.TILE -> {
                val stepX = max(bw * 1.6f, 60f)
                val stepY = max(bh * 3f, 60f)
                val out = ArrayList<Pair<Float, Float>>()
                var y = stepY / 2f
                var row = 0
                while (y < h && out.size < 400) {
                    var x = if (row % 2 == 0) stepX / 2f else stepX
                    while (x < w && out.size < 400) { out.add(x to y); x += stepX }
                    y += stepY; row++
                }
                out
            }
        }
    }

    private fun drawTextCentered(
        cs: PDPageContentStream, geo: PageGeometry, font: PDFont, text: String, fontSize: Float,
        cx: Float, cy: Float, textWidth: Float, rotationCcw: Float
    ) {
        val ux = geo.toUserX(cx, cy)
        val uy = geo.toUserY(cx, cy)
        val rad = Math.toRadians(geo.userAngle(rotationCcw).toDouble())
        cs.beginText()
        cs.setFont(font, fontSize)
        cs.setTextMatrix(Matrix.getRotateInstance(rad, ux, uy))
        cs.newLineAtOffset(-textWidth / 2f, -fontSize * 0.35f)
        cs.showText(text)
        cs.endText()
    }

    private fun drawImageCentered(
        cs: PDPageContentStream, geo: PageGeometry, image: PDImageXObject,
        cx: Float, cy: Float, w: Float, h: Float, rotationCcw: Float
    ) {
        val ux = geo.toUserX(cx, cy)
        val uy = geo.toUserY(cx, cy)
        val rad = Math.toRadians(geo.userAngle(rotationCcw).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // Unit square scaled to w x h, rotated about its centre, centre placed at (ux, uy).
        val a = w * c
        val b = w * s
        val cc = -h * s
        val d = h * c
        val e = ux - (a + cc) / 2f
        val f = uy - (b + d) / 2f
        cs.drawImage(image, Matrix(a, b, cc, d, e, f))
    }

    /** Draws [image] with its display-space bottom-left at (dx,dy), size w x h, upright on screen. */
    private fun drawImageDisplay(
        cs: PDPageContentStream, geo: PageGeometry, image: PDImageXObject,
        dx: Float, dy: Float, w: Float, h: Float
    ) {
        val rad = Math.toRadians(geo.displayAxisAngle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        cs.drawImage(image, Matrix(w * c, w * s, -h * s, h * c, geo.toUserX(dx, dy), geo.toUserY(dx, dy)))
    }

    suspend fun pageNumbers(input: File, output: File, config: PageNumberConfig): File =
        withDoc(input) { doc ->
            val font = PDType1Font.HELVETICA
            val total = doc.numberOfPages
            val first = config.startPage.coerceAtLeast(1)
            val last = if (config.endPage < 1) total else config.endPage.coerceAtMost(total)
            for (i in first..last) {
                checkActive()
                val page = doc.getPage(i - 1)
                val geo = geometry(page)
                val number = config.startNumber + (i - first)
                var text = config.format.replaceFirst("%d", "$number")
                if (text.contains("%d")) text = text.replaceFirst("%d", "$total")
                text = printable(font, text)
                if (text.isBlank()) continue
                val tw = font.getStringWidth(text) / 1000f * config.fontSize
                val dx = when (config.alignment) {
                    "left" -> 20f
                    "right" -> geo.displayWidth - tw - 20f
                    else -> geo.displayWidth / 2f - tw / 2f
                }
                val dy = if (config.placement == "top") geo.displayHeight - 18f else 12f
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setNonStrokingColor(
                        Color.red(config.color), Color.green(config.color), Color.blue(config.color)
                    )
                    val rad = Math.toRadians(geo.displayAxisAngle.toDouble())
                    cs.beginText()
                    cs.setFont(font, config.fontSize)
                    cs.setTextMatrix(Matrix.getRotateInstance(rad, geo.toUserX(dx, dy), geo.toUserY(dx, dy)))
                    cs.showText(text)
                    cs.endText()
                }
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    suspend fun headerFooter(input: File, output: File, config: HeaderFooterConfig): File =
        withDoc(input) { doc ->
            val header = config.headerText?.takeIf { it.isNotBlank() }?.let { renderLabel(doc, it, config.fontSize) }
            val footer = config.footerText?.takeIf { it.isNotBlank() }?.let { renderLabel(doc, it, config.fontSize) }
            for (i in 1..doc.numberOfPages) {
                checkActive()
                val page = doc.getPage(i - 1)
                val geo = geometry(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    header?.let {
                        drawImageDisplay(
                            cs, geo, it.image, alignX(config.headerAlignment, geo, it.w), geo.displayHeight - it.h - 4f, it.w, it.h
                        )
                    }
                    footer?.let {
                        drawImageDisplay(
                            cs, geo, it.image, alignX(config.footerAlignment, geo, it.w), 4f, it.w, it.h
                        )
                    }
                }
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    private fun alignX(alignment: String, geo: PageGeometry, w: Float): Float = when (alignment) {
        "left" -> 10f
        "right" -> geo.displayWidth - w - 10f
        else -> geo.displayWidth / 2f - w / 2f
    }

    private class Label(val image: PDImageXObject, val w: Float, val h: Float)

    /** Renders text with Android's fonts (full Unicode) and embeds it once per document. */
    private fun renderLabel(doc: PDDocument, text: String, fontSizePt: Float): Label {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePt * 2.5f
            color = Color.BLACK
            typeface = Typeface.DEFAULT
            isLinearText = true
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val bW = (bounds.width() + 20).coerceAtLeast(1)
        val bH = (fontSizePt * 4).toInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(bW, bH, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawText(text, 10f, bH - 6f, paint)
            return Label(LosslessFactory.createFromImage(doc, bmp), bW / 2.5f, bH / 2.5f)
        } finally {
            bmp.recycle()
        }
    }

    suspend fun background(input: File, output: File, config: BackgroundConfig): File =
        withDoc(input) { doc ->
            val img = config.imageUri?.let { decodeImage(it) }
            try {
                val imageX = img?.let { embed(doc, it.bitmap) }
                val gs = PDExtendedGraphicsState().apply {
                    setNonStrokingAlphaConstant(config.opacity.coerceIn(0f, 1f))
                }
                for (n in targetsOf(config.pages, doc.numberOfPages)) {
                    checkActive()
                    val page = doc.getPage(n - 1)
                    val geo = geometry(page)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true, false).use { cs ->
                        cs.saveGraphicsState()
                        cs.setGraphicsStateParameters(gs)
                        if (imageX != null) {
                            drawImageDisplay(cs, geo, imageX, 0f, 0f, geo.displayWidth, geo.displayHeight)
                        } else {
                            cs.setNonStrokingColor(
                                Color.red(config.color), Color.green(config.color), Color.blue(config.color)
                            )
                            cs.addRect(geo.left, geo.bottom, geo.boxWidth, geo.boxHeight)
                            cs.fill()
                        }
                        cs.restoreGraphicsState()
                    }
                }
            } finally {
                img?.bitmap?.recycle()
            }
            publish(output) { tmp -> saveTo(doc, tmp) }
        }

    // ------------------------------------------------------------------ images and text helpers

    private class DecodedImage(val bitmap: Bitmap, val origWidth: Float, val origHeight: Float)

    /** Reads via ContentResolver so file:// and content:// both work; downsamples to bound memory. */
    private fun decodeImage(uri: Uri, maxDimension: Int = 2400): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (context.contentResolver.openInputStream(uri)
            ?: throw AppException.FileNotFound("Cannot open image")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PdfProcessingError.ProcessingFailed("Unsupported or corrupt image")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = (context.contentResolver.openInputStream(uri)
            ?: throw AppException.FileNotFound("Cannot open image")).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw PdfProcessingError.ProcessingFailed("Unsupported or corrupt image")
        return DecodedImage(bmp, bounds.outWidth.toFloat(), bounds.outHeight.toFloat())
    }

    /** Opaque bitmaps -> JPEG; bitmaps with alpha -> lossless so transparency is kept. */
    private fun embed(doc: PDDocument, bmp: Bitmap): PDImageXObject =
        if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp)
        else JPEGFactory.createFromImage(doc, bmp, 0.9f)

    private fun imagePage(doc: PDDocument, uri: Uri, config: ImageInsertionConfig): PDPage {
        val img = decodeImage(uri)
        try {
            val pw = config.pageWidth.coerceAtLeast(1f)
            val ph = config.pageHeight.coerceAtLeast(1f)
            val (w, h) = ImageLayout.fit(
                config.fitMode, img.origWidth, img.origHeight, pw - 2 * config.margin, ph - 2 * config.margin
            )
            val page = PDPage(PDRectangle(pw, ph))
            val x = embed(doc, img.bitmap)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(x, (pw - w) / 2f, (ph - h) / 2f, w, h)
            }
            return page
        } finally {
            img.bitmap.recycle()
        }
    }

    private fun nativeSizeImagePage(doc: PDDocument, uri: Uri): PDPage {
        val img = decodeImage(uri)
        try {
            val w = img.origWidth
            val h = img.origHeight
            val page = PDPage(PDRectangle(w, h))
            val x = embed(doc, img.bitmap)
            PDPageContentStream(doc, page).use { cs -> cs.drawImage(x, 0f, 0f, w, h) }
            return page
        } finally {
            img.bitmap.recycle()
        }
    }

    /** Helvetica is WinAnsi only; drop characters it cannot encode instead of failing the page. */
    private fun printable(font: PDFont, text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            try {
                font.encode(ch.toString())
                sb.append(ch)
            } catch (_: Exception) { }
        }
        return sb.toString()
    }
}
