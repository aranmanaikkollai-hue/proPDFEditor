package com.propdf.security.redaction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.PdfProcessingError
import com.propdf.security.encryption.PdfSecurityException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One area to redact, in the DISPLAY space of its page: points, origin at the bottom-left of the page
 * as the viewer shows it (after /Rotate, inside the CropBox) - the space `android.graphics.pdf.PdfRenderer`
 * reports and the Redaction screen marks in. [label] is optional white text drawn inside the box.
 */
data class RedactionArea(
    val pageNumber: Int,
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val label: String? = null
)

/**
 * Secure redaction on PDFBox Android 2.0.27.0 + the platform PdfRenderer.
 *
 * DESIGN - "burn in and rebuild". Every page that has at least one redaction area is rendered to a
 * bitmap, the areas are painted solid black INTO THE BITMAP (so the original pixels never reach the
 * output), and the page is replaced by a brand-new page whose only content is that one image. Pages
 * without redactions are carried over unchanged (after sanitising references that could drag removed
 * pages back in). The output is a NEW document, so catalog-level data (Info, XMP, outlines, names,
 * AcroForm, embedded files, JavaScript, thumbnails) is not carried over.
 *
 * Why rasterise: removing selected glyphs/images/vectors from a content stream cannot be verified
 * without running real PDFs through every font type, and a failed removal is a silent leak. A page
 * rebuilt from pixels has nothing left to leak, and that can be checked structurally.
 *
 * What this guarantees for redacted pages (all verified before anything is published):
 *  - no text, vector or image operators except one image draw; no fonts; no annotations or form widgets;
 *  - text extraction returns nothing;
 *  - the pixels inside every area are black in the embedded image (or equal what was drawn when a label is used);
 *  - the page keeps its on-screen size.
 * What it does NOT do: it cannot redact only part of a page while keeping the rest of that page as
 * text/vector (the whole page becomes an image: no selectable text, links or form fields on it,
 * lower sharpness, larger file), and it does not touch pages that have no redaction areas.
 *
 * Fail-closed rules: the input is never modified; encrypted or signed input is refused; output is written
 * to a temp file, verified, and only then renamed into place; any failure or cancellation deletes the temp file.
 */
class PdfBoxRedactionEngine(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    data class Options(
        /** Render resolution for redacted pages. */
        val dpi: Int = 150,
        /** Upper bound for the longer side of a rendered page, to bound memory. */
        val maxLongSidePx: Int = 4096
    )

    data class Report(
        val totalPages: Int,
        val redactedPages: List<Int>,
        val keptPages: Int,
        val dpi: Int,
        val checksPassed: List<String>
    )

    private class RectPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private class PagePlan(
        val pageNumber: Int,
        val displayWidth: Float,
        val displayHeight: Float,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val rects: List<RectPx>,
        val labels: List<String?>,
        val expectedCrc: LongArray = LongArray(rects.size)
    ) {
        val hasLabels: Boolean get() = labels.any { !it.isNullOrEmpty() }
    }

    // ------------------------------------------------------------------ public API

    /** Redacts [areas] of [input] into [output]. Throws a typed exception; never publishes unverified output. */
    suspend fun redact(input: File, output: File, areas: List<RedactionArea>, options: Options = Options()): Report {
        if (areas.isEmpty()) throw PdfSecurityException.RedactionNoRegions()
        if (!input.exists() || input.length() == 0L) throw AppException.FileNotFound("PDF file is missing or empty")

        val src = openSource(input)
        try {
            val total = src.numberOfPages
            val plans = plan(src, areas, options)
            val redactedSet = plans.map { it.pageNumber }.toSet()

            // Evidence gathered BEFORE rewriting: what text lived on the pages we are about to replace,
            // and what text legitimately remains on kept pages (those words are not leaks).
            val removedWords = HashSet<String>()
            val keptWords = HashSet<String>()
            val keptText = HashMap<Int, String>()
            for (p in 1..total) {
                checkActive()
                val text = pageText(src, p)
                if (p in redactedSet) removedWords.addAll(words(text)) else {
                    keptWords.addAll(words(text)); keptText[p] = normalize(text)
                }
            }
            val sensitive = removedWords - keptWords - PDF_VOCABULARY

            var checks: List<String> = emptyList()
            publish(output) { tmp ->
                buildOutput(input, src, plans, total, tmp)
                checkActive()
                checks = verify(tmp, total, plans, keptText, sensitive)
            }
            return Report(total, plans.map { it.pageNumber }, total - plans.size, options.dpi, checks)
        } finally {
            closeQuietly(src)
        }
    }

    /** 1-based numbers of pages whose extracted text contains [text]. Read-only. */
    suspend fun pagesContainingText(input: File, text: String, caseSensitive: Boolean): List<Int> {
        if (text.isEmpty()) return emptyList()
        val src = openSource(input)
        try {
            val hits = ArrayList<Int>()
            for (p in 1..src.numberOfPages) {
                checkActive()
                if (pageText(src, p).contains(text, ignoreCase = !caseSensitive)) hits.add(p)
            }
            return hits
        } finally {
            closeQuietly(src)
        }
    }

    // ------------------------------------------------------------------ loading and planning

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

    private fun openSource(file: File): PDDocument {
        val doc = try {
            PDDocument.load(file, "", memory())
        } catch (e: InvalidPasswordException) {
            throw PdfSecurityException.RedactionProtectedSource()
        } catch (e: IOException) {
            throw PdfProcessingError.CorruptedFile("PDF could not be opened", e)
        }
        try {
            if (doc.isEncrypted) throw PdfSecurityException.RedactionProtectedSource()
            val signed = doc.signatureDictionaries.isNotEmpty() ||
                doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
                    ?.containsKey(COSName.getPDFName("DocMDP")) == true
            if (signed) throw PdfSecurityException.RedactionSignedDocument()
            if (doc.numberOfPages < 1) throw PdfProcessingError.CorruptedFile("PDF has no pages")
            return doc
        } catch (t: Throwable) {
            closeQuietly(doc)
            throw t
        }
    }

    private class Display(val width: Float, val height: Float)

    private fun display(page: PDPage): Display {
        val box = page.cropBox
        val quarter = Math.floorMod((page.rotation / 90) * 90, 360).let { it == 90 || it == 270 }
        return if (quarter) Display(box.height, box.width) else Display(box.width, box.height)
    }

    private fun plan(src: PDDocument, areas: List<RedactionArea>, options: Options): List<PagePlan> {
        val total = src.numberOfPages
        val byPage = areas.groupBy { it.pageNumber }.toSortedMap()
        val plans = ArrayList<PagePlan>()
        for ((pageNumber, list) in byPage) {
            if (pageNumber !in 1..total) throw PdfSecurityException.RedactionInvalidRegion()
            val d = display(src.getPage(pageNumber - 1))
            if (d.width < 1f || d.height < 1f) throw PdfProcessingError.CorruptedFile("Page has no size")
            val longSide = max(d.width, d.height)
            val scale = min(options.dpi.coerceIn(36, 600) / 72f, options.maxLongSidePx / longSide).coerceAtLeast(0.5f)
            val bw = ceil(d.width * scale).toInt().coerceAtLeast(1)
            val bh = ceil(d.height * scale).toInt().coerceAtLeast(1)
            val sx = bw / d.width
            val sy = bh / d.height
            val rects = ArrayList<RectPx>()
            val labels = ArrayList<String?>()
            for (a in list) {
                if (!a.left.isFinite() || !a.bottom.isFinite() || !a.width.isFinite() || !a.height.isFinite()) {
                    throw PdfSecurityException.RedactionInvalidRegion()
                }
                val l = max(0f, a.left); val b = max(0f, a.bottom)
                val r = min(d.width, a.left + a.width); val t = min(d.height, a.bottom + a.height)
                if (r - l < 1f || t - b < 1f) throw PdfSecurityException.RedactionInvalidRegion()
                // Expand by one pixel each way so anti-aliased glyph edges at the border are covered too.
                val pl = (floor(l * sx).toInt() - 1).coerceAtLeast(0)
                val pr = (ceil(r * sx).toInt() + 1).coerceAtMost(bw)
                val pt = (floor((d.height - t) * sy).toInt() - 1).coerceAtLeast(0)
                val pb = (ceil((d.height - b) * sy).toInt() + 1).coerceAtMost(bh)
                if (pr <= pl || pb <= pt) throw PdfSecurityException.RedactionInvalidRegion()
                rects.add(RectPx(pl, pt, pr, pb)); labels.add(a.label)
            }
            plans.add(PagePlan(pageNumber, d.width, d.height, bw, bh, rects, labels))
        }
        return plans
    }

    // ------------------------------------------------------------------ building

    private suspend fun buildOutput(
        inputFile: File, src: PDDocument, plans: List<PagePlan>, total: Int, tmp: File
    ) {
        val dest = PDDocument(memory())
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount != total) {
                throw PdfSecurityException.RedactionVerificationFailed("renderer and parser disagree on page count")
            }
            val planByPage = plans.associateBy { it.pageNumber }
            for (p in 1..total) {
                checkActive()
                val plan = planByPage[p]
                if (plan != null) addRedactedPage(dest, renderer, plan) else addKeptPage(dest, src, p)
            }
            tmp.outputStream().buffered().use { dest.save(it) }
        } finally {
            try { renderer?.close() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
            closeQuietly(dest)
        }
    }

    private fun addRedactedPage(dest: PDDocument, renderer: PdfRenderer, plan: PagePlan) {
        val bmp = Bitmap.createBitmap(plan.bitmapWidth, plan.bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            renderer.openPage(plan.pageNumber - 1).use { page ->
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            val canvas = Canvas(bmp)
            val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
            for (r in plan.rects) canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), black)

            // The original pixels are gone only if the fill really took effect: check it, do not assume it.
            for (r in plan.rects) {
                if (!regionIsBlack(bmp, r)) {
                    throw PdfSecurityException.RedactionVerificationFailed("page ${plan.pageNumber}: fill did not cover the area")
                }
            }

            // Optional label, drawn only after the purity check, white on black, clipped to its box.
            if (plan.hasLabels) {
                val text = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 8f * plan.bitmapWidth / plan.displayWidth }
                plan.rects.forEachIndexed { i, r ->
                    val label = plan.labels[i]
                    if (!label.isNullOrEmpty()) {
                        canvas.save()
                        canvas.clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                        canvas.drawText(label, r.left + text.textSize * 0.25f, r.top + text.textSize * 1.25f, text)
                        canvas.restore()
                    }
                }
            }
            plan.rects.forEachIndexed { i, r -> plan.expectedCrc[i] = regionCrc(bmp, r) }

            bmp.setHasAlpha(false)
            val image = LosslessFactory.createFromImage(dest, bmp)
            val newPage = PDPage(PDRectangle(plan.displayWidth, plan.displayHeight))
            dest.addPage(newPage)
            PDPageContentStream(dest, newPage).use { cs ->
                cs.drawImage(image, 0f, 0f, plan.displayWidth, plan.displayHeight)
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun regionIsBlack(bmp: Bitmap, r: RectPx): Boolean {
        val row = IntArray(r.width)
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, r.width, r.left, y, r.width, 1)
            for (px in row) if (px != Color.BLACK) return false
        }
        return true
    }

    private fun regionCrc(bmp: Bitmap, r: RectPx): Long {
        val crc = CRC32()
        val row = IntArray(r.width)
        val bytes = ByteArray(r.width * 4)
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, r.width, r.left, y, r.width, 1)
            var o = 0
            for (px in row) {
                bytes[o++] = (px ushr 24).toByte(); bytes[o++] = (px ushr 16).toByte()
                bytes[o++] = (px ushr 8).toByte(); bytes[o++] = px.toByte()
            }
            crc.update(bytes, 0, bytes.size)
        }
        return crc.value
    }

    private val removedPageKeys = listOf("Thumb", "Metadata", "PieceInfo", "B", "AA", "LastModified", "StructParents")

    /** Carries a page over unchanged, minus references that could pull removed pages back into the file. */
    private fun addKeptPage(dest: PDDocument, src: PDDocument, pageNumber: Int) {
        val imported = dest.importPage(src.getPage(pageNumber - 1))
        removedPageKeys.forEach { imported.cosObject.removeItem(COSName.getPDFName(it)) }
        try {
            for (a in imported.annotations) sanitizeAnnotation(a.cosObject)
        } catch (_: Exception) {
            // Unreadable annotation list: drop it rather than risk carrying references along.
            imported.cosObject.removeItem(COSName.ANNOTS)
        }
        pruneUnusedXObjects(imported)
    }

    private fun sanitizeAnnotation(d: COSDictionary) {
        // /P, /Parent (form field tree), /IRT, /Popup and destinations can all point at other page or
        // field objects - including pages that were redacted - which would drag their content in.
        for (k in listOf("P", "Parent", "IRT", "Popup", "Dest", "AA")) d.removeItem(COSName.getPDFName(k))
        val action = d.getDictionaryObject(COSName.A) as? COSDictionary
        if (action != null && action.getNameAsString(COSName.S) != "URI") d.removeItem(COSName.A)
    }

    /**
     * Pages often share one /Resources dictionary, so a form XObject that only a redacted page uses can
     * still be listed in a kept page's resources. Removes top-level XObject entries the page content never
     * draws, on a private copy of the dictionary. Skips pruning whenever that could change rendering.
     */
    private fun pruneUnusedXObjects(page: PDPage) {
        try {
            val resources = page.resources ?: return
            val xobjects = resources.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return
            if (xobjects.keySet().isEmpty()) return

            val used = HashSet<COSName>()
            val parser = PDFStreamParser(page)
            parser.parse()
            var lastName: COSName? = null
            for (token in parser.tokens) {
                if (token is COSName) lastName = token
                else if (token is Operator) {
                    if (token.name == "Do") lastName?.let { used.add(it) }
                    lastName = null
                }
            }
            // A form without its own /Resources inherits the page's; keep everything in that case.
            for (name in used) {
                val x = xobjects.getDictionaryObject(name)
                if (x is COSStream && x.getDictionaryObject(COSName.RESOURCES) == null &&
                    x.getNameAsString(COSName.SUBTYPE) == "Form"
                ) return
            }
            val keep = COSDictionary()
            for (name in xobjects.keySet()) if (name in used) keep.setItem(name, xobjects.getItem(name))
            val resourcesCopy = COSDictionary(resources.cosObject)
            resourcesCopy.setItem(COSName.XOBJECT, keep)
            page.resources = PDResources(resourcesCopy)
        } catch (_: Exception) {
            // Keep the page as is; the leak scan below is the backstop.
        }
    }

    // ------------------------------------------------------------------ verification (fail closed)

    private suspend fun verify(
        file: File, total: Int, plans: List<PagePlan>,
        keptText: Map<Int, String>, sensitive: Set<String>
    ): List<String> {
        val doc = try {
            PDDocument.load(file, "", memory())
        } catch (e: Exception) {
            throw PdfSecurityException.RedactionVerificationFailed("output cannot be reopened: ${e.javaClass.simpleName}")
        }
        val passed = ArrayList<String>()
        try {
            fun fail(msg: String): Nothing = throw PdfSecurityException.RedactionVerificationFailed(msg)

            if (doc.isEncrypted) fail("output is unexpectedly encrypted")
            if (doc.numberOfPages != total) fail("page count changed")
            passed.add("page count preserved ($total)")

            // Catalog / document level: nothing that could carry removed information.
            val cat = doc.documentCatalog.cosObject
            for (k in listOf("Metadata", "Outlines", "Names", "Dests", "AcroForm", "OpenAction", "AA", "Threads", "StructTreeRoot", "OCProperties", "PieceInfo")) {
                if (cat.containsKey(COSName.getPDFName(k))) fail("catalog still has /$k")
            }
            val info = doc.document.trailer.getDictionaryObject(COSName.INFO) as? COSDictionary
            if (info != null && info.keySet().isNotEmpty()) fail("document information dictionary is not empty")
            passed.add("document-level metadata, outlines, names, forms and JavaScript are not carried over")

            for (plan in plans) {
                checkActive()
                val page = doc.getPage(plan.pageNumber - 1)
                if (page.cosObject.containsKey(COSName.ANNOTS) && page.annotations.isNotEmpty()) fail("page ${plan.pageNumber} still has annotations")
                for (k in listOf("Metadata", "Thumb", "PieceInfo", "AA", "B")) {
                    if (page.cosObject.containsKey(COSName.getPDFName(k))) fail("page ${plan.pageNumber} still has /$k")
                }
                if (PDFTextStripper().also { it.startPage = plan.pageNumber; it.endPage = plan.pageNumber }.getText(doc).isNotBlank()) {
                    fail("page ${plan.pageNumber} still yields extractable text")
                }
                val res = page.resources ?: fail("page ${plan.pageNumber} has no resources")
                if (res.fontNames.iterator().hasNext()) fail("page ${plan.pageNumber} still references fonts")
                val names = res.xObjectNames.toList()
                if (names.size != 1 || !res.isImageXObject(names[0])) fail("page ${plan.pageNumber} is not a single image")
                val parser = PDFStreamParser(page); parser.parse()
                val allowed = setOf("q", "Q", "cm", "Do")
                for (t in parser.tokens) if (t is Operator && t.name !in allowed) fail("page ${plan.pageNumber} has operator ${t.name}")
                if (parser.tokens.count { it is Operator && it.name == "Do" } != 1) fail("page ${plan.pageNumber} does not draw exactly one image")

                val box = page.mediaBox
                if (kotlin.math.abs(box.width - plan.displayWidth) > 0.5f || kotlin.math.abs(box.height - plan.displayHeight) > 0.5f) {
                    fail("page ${plan.pageNumber} changed size")
                }
                val image = (res.getXObject(names[0]) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject).image
                try {
                    if (image.width != plan.bitmapWidth || image.height != plan.bitmapHeight) fail("page ${plan.pageNumber} image size changed")
                    plan.rects.forEachIndexed { i, r ->
                        if (regionCrc(image, r) != plan.expectedCrc[i]) fail("page ${plan.pageNumber}: redacted pixels differ from what was drawn")
                        if (!plan.hasLabels && !regionIsBlack(image, r)) fail("page ${plan.pageNumber}: redacted area is not black")
                    }
                } finally {
                    image.recycle()
                }
            }
            passed.add("redacted pages hold a single image, no text, fonts or annotations, and the areas are black in the embedded pixels")

            for ((p, expected) in keptText) {
                checkActive()
                if (normalize(pageText(doc, p)) != expected) fail("kept page $p text changed")
            }
            passed.add("pages without redactions keep their text")

            if (sensitive.isNotEmpty()) {
                val leaked = scanForWords(doc, sensitive)
                if (leaked != null) fail("removed content is still present in the file")
                passed.add("no removed word (${sensitive.size} checked) found in extracted text, content streams or strings")
            } else {
                passed.add("no text existed on the redacted pages; text scan skipped")
            }
            return passed
        } finally {
            closeQuietly(doc)
        }
    }

    /** Walks everything reachable from the trailer; returns a leaked word or null. Heuristic backstop only. */
    private suspend fun scanForWords(doc: PDDocument, sensitive: Set<String>): String? {
        val visited = IdentityHashMap<Any, Boolean>()
        val stack = ArrayList<COSBase>()
        stack.add(doc.document.trailer)
        var steps = 0
        while (stack.isNotEmpty()) {
            if (++steps % 500 == 0) checkActive()
            var node: COSBase = stack.removeAt(stack.size - 1)
            if (node is COSObject) node = node.getObject() ?: continue
            if (visited.put(node, true) != null) continue
            when (node) {
                is COSString -> firstHit(node.string, sensitive)?.let { return it }
                is COSArray -> for (i in 0 until node.size()) node.get(i)?.let { stack.add(it) }
                is COSDictionary -> {
                    for (key in node.keySet()) node.getItem(key)?.let { stack.add(it) }
                    if (node is COSStream && isTextLikeStream(node)) {
                        val bytes = try {
                            node.createInputStream().use { it.readBytes() }
                        } catch (e: Exception) { null }
                        if (bytes != null && bytes.size <= MAX_SCAN_BYTES && !looksLikeCMap(bytes)) {
                            firstHit(String(bytes, Charsets.ISO_8859_1), sensitive)?.let { return it }
                            firstHit(String(bytes, Charsets.UTF_16BE), sensitive)?.let { return it }
                        }
                    }
                }
                else -> {}
            }
        }
        return null
    }

    private fun isTextLikeStream(s: COSStream): Boolean {
        if (s.getNameAsString(COSName.SUBTYPE) == "Image") return false
        for (k in listOf("Length1", "Length2", "Length3")) if (s.containsKey(COSName.getPDFName(k))) return false
        val sub = s.getNameAsString(COSName.SUBTYPE)
        if (sub == "Type1C" || sub == "CIDFontType0C" || sub == "OpenType") return false
        if (s.containsKey(COSName.getPDFName("N"))) return false      // ICC colour profile
        return true
    }

    /** ToUnicode/encoding CMaps are PostScript-like boilerplate whose words are not page content. */
    private fun looksLikeCMap(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, min(bytes.size, 512), Charsets.ISO_8859_1)
        return head.contains("begincmap") || head.contains("CIDInit") || head.contains("%!PS")
    }

    private fun firstHit(text: String, sensitive: Set<String>): String? {
        for (m in WORD.findAll(text.lowercase())) if (m.value in sensitive) return m.value
        return null
    }

    // ------------------------------------------------------------------ text helpers

    private fun pageText(doc: PDDocument, page: Int): String =
        PDFTextStripper().also { it.startPage = page; it.endPage = page }.getText(doc)

    private fun words(text: String): Set<String> =
        WORD.findAll(text.lowercase()).map { it.value }.toSet()

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------ output

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()

    /** Temp sibling -> verify -> rename. The temp file is deleted on any failure or cancellation. */
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

    private fun closeQuietly(doc: PDDocument) {
        try { doc.close() } catch (_: Exception) { }
    }

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}]{4,}")
        /** Common PDF/PostScript vocabulary that can occur in structural streams; never treated as leaked page text. */
        val PDF_VOCABULARY = setOf(
            "begin", "cmap", "cidinit", "procset", "findresource", "dict", "adobe", "identity", "ordering",
            "supplement", "type", "font", "name", "text", "image", "length", "filter", "width", "height",
            "stream", "object", "flate", "page", "pages", "kids", "count", "parent", "resources", "contents",
            "mediabox", "xobject", "form", "group", "color", "space", "rgb", "gray", "info", "title", "producer"
        )
        const val MAX_SCAN_BYTES = 16 * 1024 * 1024
    }
}
