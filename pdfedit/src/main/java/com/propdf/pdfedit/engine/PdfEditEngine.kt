package com.propdf.pdfedit.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.propdf.pdfedit.content.ContentRewriter
import com.propdf.pdfedit.content.ContentScanner
import com.propdf.pdfedit.content.Instr
import com.propdf.pdfedit.content.Operand
import com.propdf.pdfedit.content.SiteAction
import com.propdf.pdfedit.content.TextSanitizer
import com.propdf.pdfedit.geometry.PageGeometry
import com.propdf.pdfedit.geometry.RectPds
import com.propdf.pdfedit.ops.AddImage
import com.propdf.pdfedit.ops.AddText
import com.propdf.pdfedit.ops.DeleteImage
import com.propdf.pdfedit.ops.EditOperation
import com.propdf.pdfedit.ops.FontChoice
import com.propdf.pdfedit.ops.FontSource
import com.propdf.pdfedit.ops.ImageRef
import com.propdf.pdfedit.ops.TransformImage
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Replays edit operations on a PDF and writes a new file. The base file is only read.
 *
 * All coordinates in operations are Page Display Space (see PageGeometry). The caller must have initialized
 * PDFBox-Android (PDFBoxResourceLoader.init) once. Not thread-safe per document; use one call at a time per file.
 *
 * Supported: add text, add images, move/resize/delete images that the page content draws directly. Not supported:
 * encrypted files (refused), inline images and images inside form XObjects (listed by [inspect], not editable),
 * editing existing text.
 */
class PdfEditEngine(
    private val regularFont: FontSource,
    private val boldFont: FontSource,
    private val maxImageDimension: Int = 4096
) {
    constructor(context: Context) : this(BundledFonts.regular(context), BundledFonts.bold(context))

    // ---- reading ---------------------------------------------------------------------------------------------

    /** Describes the pages and the images that can be edited. Read-only. */
    fun inspect(file: File): DocumentInfo = load(file).use { doc ->
        val pages = ArrayList<PageInfo>()
        for (i in 0 until doc.numberOfPages) {
            val page = doc.getPage(i)
            val geometry = geometryOf(page)
            val instrs = parseInstrs(page)
            val isImage = imagePredicate(page)
            val sites = ContentScanner.imageSites(instrs, isImage)
            val images = sites.map { ImageInfo(ImageRef(i, it.imageOrdinal, it.xObjectName), geometry.imagePlacementToPds(it.ctm)) }
            val inline = instrs.count { it.op == "BI" }
            val forms = instrs.count { it.op == "Do" && it.operands.lastOrNull().let { n -> n is Operand.Name && !isImage(n.name) } }
            pages.add(PageInfo(i, geometry.displayWidth, geometry.displayHeight, geometry.rotation, images, inline, forms))
        }
        DocumentInfo(doc.numberOfPages, isSigned(doc), isCertified(doc), pages)
    }

    // ---- writing ---------------------------------------------------------------------------------------------

    /**
     * Applies [ops] to a copy of [base] and writes the result to [out], then reopens [out] and checks it. If the
     * check fails, [out] is deleted and [EditVerificationException] is thrown.
     */
    fun materialize(base: File, ops: List<EditOperation>, out: File): EditReport {
        if (out.canonicalFile == base.canonicalFile) {
            throw EditRefusedException(EditRefusedException.Reason.SAME_AS_SOURCE, "The output must not be the original file")
        }
        val warnings = LinkedHashSet<EditWarning>()
        val replaced = LinkedHashMap<String, List<String>>()
        val skipped = LinkedHashMap<String, EditWarning>()
        val expectations = LinkedHashMap<Int, Expectation>()
        var pageCount = 0

        load(base).use { doc ->
            pageCount = doc.numberOfPages
            for (op in ops) {
                if (op.pageIndex < 0 || op.pageIndex >= pageCount) {
                    throw EditRefusedException(EditRefusedException.Reason.PAGE_OUT_OF_RANGE, "No page " + op.pageIndex)
                }
            }
            if (isSigned(doc)) warnings.add(EditWarning.SIGNATURE_WILL_BE_INVALID)
            if (isCertified(doc)) warnings.add(EditWarning.CERTIFIED_DOCUMENT)

            val fonts = HashMap<Any, PDFont>()
            for ((pageIndex, pageOps) in ops.groupBy { it.pageIndex }.toSortedMap()) {
                val page = doc.getPage(pageIndex)
                val geometry = geometryOf(page)
                val baseInstrs = parseInstrs(page)
                val expect = Expectation(
                    images = ContentScanner.imageSites(baseInstrs, imagePredicate(page)).size,
                    showOps = countShowOps(baseInstrs)
                )
                expectations[pageIndex] = expect

                val imageOps = pageOps.filter { it is TransformImage || it is DeleteImage }
                if (imageOps.isNotEmpty()) {
                    expect.images -= rewriteImages(doc, page, geometry, baseInstrs, imageOps, warnings, skipped)
                }
                val addOps = pageOps.filter { it is AddText || it is AddImage }
                if (addOps.isNotEmpty()) {
                    appendContent(doc, page, geometry, addOps, fonts, warnings, replaced, skipped, expect)
                }
            }
            doc.save(out)
        }

        val problems = verify(out, pageCount, expectations)
        if (problems.isNotEmpty()) {
            out.delete()
            throw EditVerificationException(problems)
        }
        return EditReport(warnings, replaced, skipped, pageCount)
    }

    // ---- image rewriting -------------------------------------------------------------------------------------

    /** Returns how many image drawings were deleted. */
    private fun rewriteImages(
        doc: PDDocument,
        page: PDPage,
        geometry: PageGeometry,
        instrs: List<Instr>,
        imageOps: List<EditOperation>,
        warnings: MutableSet<EditWarning>,
        skipped: MutableMap<String, EditWarning>
    ): Int {
        val isImage = imagePredicate(page)
        val sites = ContentScanner.imageSites(instrs, isImage).associateBy { it.imageOrdinal }
        val actions = LinkedHashMap<Int, SiteAction>()
        val owner = HashMap<Int, String>()
        for (op in imageOps) {
            val target: ImageRef
            val action: SiteAction
            when (op) {
                is TransformImage -> {
                    target = op.target
                    if (!validRect(op.newBounds)) {
                        skipped[op.id] = EditWarning.EMPTY_BOUNDS
                        warnings.add(EditWarning.EMPTY_BOUNDS)
                        continue
                    }
                    action = SiteAction.Place(geometry.placementMatrixFor(op.newBounds))
                }
                is DeleteImage -> {
                    target = op.target
                    action = SiteAction.Delete
                }
                else -> continue
            }
            val site = sites[target.ordinal]
            if (site == null || site.xObjectName != target.xObjectName) {
                skipped[op.id] = EditWarning.STALE_IMAGE_REFERENCE
                warnings.add(EditWarning.STALE_IMAGE_REFERENCE)
                continue
            }
            actions[target.ordinal] = action // the last operation on a drawing wins
            owner[target.ordinal] = op.id
        }
        if (actions.isEmpty()) return 0

        val result = ContentRewriter.rewrite(instrs, isImage, actions)
        for (ordinal in result.skippedOrdinals) {
            warnings.add(EditWarning.IMAGE_NOT_TRANSFORMABLE)
            owner[ordinal]?.let { skipped[it] = EditWarning.IMAGE_NOT_TRANSFORMABLE }
        }
        writeContents(doc, page, tokensOf(result.instrs))
        return actions.values.count { it is SiteAction.Delete }
    }

    private fun writeContents(doc: PDDocument, page: PDPage, tokens: List<Any>) {
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
    }

    private fun parseInstrs(page: PDPage): List<Instr> {
        val parser = PDFStreamParser(page)
        parser.parse()
        val out = ArrayList<Instr>()
        val pending = ArrayList<Any>()
        for (token in parser.tokens) {
            if (token is Operator) {
                val source = ArrayList<Any>(pending)
                source.add(token)
                out.add(Instr(token.name, pending.map(::toOperand), source))
                pending.clear()
            } else {
                pending.add(token)
            }
        }
        if (pending.isNotEmpty()) out.add(Instr("", pending.map(::toOperand), ArrayList<Any>(pending)))
        return out
    }

    private fun toOperand(token: Any): Operand = when (token) {
        is COSNumber -> Operand.Num(token.doubleValue())
        is COSName -> Operand.Name(token.name)
        else -> Operand.Other
    }

    private fun tokensOf(instrs: List<Instr>): List<Any> {
        val tokens = ArrayList<Any>()
        for (ins in instrs) {
            val source = ins.source
            if (source is List<*>) {
                source.forEach { if (it != null) tokens.add(it) }
            } else {
                for (o in ins.operands) {
                    when (o) {
                        is Operand.Num -> tokens.add(COSFloat(o.value.toFloat()))
                        is Operand.Name -> tokens.add(COSName.getPDFName(o.name))
                        Operand.Other -> {}
                    }
                }
                tokens.add(Operator.getOperator(ins.op))
            }
        }
        return tokens
    }

    // ---- adding content --------------------------------------------------------------------------------------

    private fun appendContent(
        doc: PDDocument,
        page: PDPage,
        geometry: PageGeometry,
        addOps: List<EditOperation>,
        fonts: MutableMap<Any, PDFont>,
        warnings: MutableSet<EditWarning>,
        replaced: MutableMap<String, List<String>>,
        skipped: MutableMap<String, EditWarning>,
        expect: Expectation
    ) {
        // resetContext = true wraps the existing content in q/Q so our drawing starts from a clean graphics state.
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            val m = geometry.displayToUser
            cs.transform(Matrix(m.a.toFloat(), m.b.toFloat(), m.c.toFloat(), m.d.toFloat(), m.e.toFloat(), m.f.toFloat()))
            for (op in addOps) {
                when (op) {
                    is AddText -> drawText(doc, cs, op, fonts, warnings, replaced, skipped, expect)
                    is AddImage -> drawImage(doc, cs, op, warnings, skipped, expect)
                    else -> {}
                }
            }
            cs.restoreGraphicsState()
        }
    }

    private fun drawText(
        doc: PDDocument,
        cs: PDPageContentStream,
        op: AddText,
        fonts: MutableMap<Any, PDFont>,
        warnings: MutableSet<EditWarning>,
        replaced: MutableMap<String, List<String>>,
        skipped: MutableMap<String, EditWarning>,
        expect: Expectation
    ) {
        if (!(op.fontSizePt > 0.0) || !op.fontSizePt.isFinite() || !op.origin.x.isFinite() || !op.origin.y.isFinite()) {
            skipped[op.id] = EditWarning.EMPTY_BOUNDS
            warnings.add(EditWarning.EMPTY_BOUNDS)
            return
        }
        val font = fontFor(doc, op.font, fonts, warnings)
        val clean = TextSanitizer.sanitize(op.text) { s ->
            try {
                font.encode(s)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (clean.dropped.isNotEmpty()) {
            warnings.add(EditWarning.CHARACTERS_REPLACED)
            replaced[op.id] = clean.dropped
        }
        if (clean.text.isBlank()) {
            skipped[op.id] = EditWarning.EMPTY_BOUNDS
            warnings.add(EditWarning.EMPTY_BOUNDS)
            return
        }
        val size = op.fontSizePt.toFloat()
        cs.saveGraphicsState()
        cs.setNonStrokingColor((op.colorRgb shr 16) and 0xFF, (op.colorRgb shr 8) and 0xFF, op.colorRgb and 0xFF)
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(op.origin.x.toFloat(), op.origin.y.toFloat())
        var first = true
        for (line in clean.text.split("\n")) {
            if (!first) cs.newLineAtOffset(0f, -size * 1.2f)
            first = false
            if (line.isNotEmpty()) {
                cs.showText(line)
                expect.showOps += 1
            }
        }
        cs.endText()
        cs.restoreGraphicsState()
    }

    private fun fontFor(
        doc: PDDocument,
        choice: FontChoice,
        cache: MutableMap<Any, PDFont>,
        warnings: MutableSet<EditWarning>
    ): PDFont {
        val key: Any = when (choice) {
            FontChoice.BundledRegular -> "regular"
            FontChoice.BundledBold -> "bold"
            is FontChoice.DeviceFont -> choice
        }
        cache[key]?.let { return it }
        fun loadFont(source: FontSource): PDFont = source.open().use { PDType0Font.load(doc, it, true) }
        val font = try {
            when (choice) {
                FontChoice.BundledRegular -> loadFont(regularFont)
                FontChoice.BundledBold -> loadFont(boldFont)
                is FontChoice.DeviceFont -> loadFont(choice.source)
            }
        } catch (e: Exception) {
            if (choice is FontChoice.DeviceFont) {
                warnings.add(EditWarning.FONT_UNAVAILABLE)
                cache["regular"] ?: loadFont(regularFont).also { cache["regular"] = it }
            } else {
                throw e
            }
        }
        cache[key] = font
        return font
    }

    private fun drawImage(
        doc: PDDocument,
        cs: PDPageContentStream,
        op: AddImage,
        warnings: MutableSet<EditWarning>,
        skipped: MutableMap<String, EditWarning>,
        expect: Expectation
    ) {
        if (!validRect(op.bounds)) {
            skipped[op.id] = EditWarning.EMPTY_BOUNDS
            warnings.add(EditWarning.EMPTY_BOUNDS)
            return
        }
        val image = try {
            createImage(doc, op.image.readBytes())
        } catch (e: Exception) {
            null
        }
        if (image == null) {
            skipped[op.id] = EditWarning.IMAGE_UNREADABLE
            warnings.add(EditWarning.IMAGE_UNREADABLE)
            return
        }
        cs.drawImage(image, op.bounds.left.toFloat(), op.bounds.bottom.toFloat(), op.bounds.width.toFloat(), op.bounds.height.toFloat())
        expect.images += 1
    }

    /** JPEG data is embedded as it is; anything else is decoded (down-sampled if huge) and stored losslessly. */
    private fun createImage(doc: PDDocument, bytes: ByteArray): PDImageXObject? {
        if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            try {
                return JPEGFactory.createFromStream(doc, ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                // fall through to the bitmap path
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxImageDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            LosslessFactory.createFromImage(doc, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // ---- verification ----------------------------------------------------------------------------------------

    private class Expectation(var images: Int, var showOps: Int)

    private fun verify(out: File, pageCount: Int, expectations: Map<Int, Expectation>): List<String> {
        val problems = ArrayList<String>()
        try {
            load(out).use { doc ->
                if (doc.numberOfPages != pageCount) {
                    problems.add("page count changed from " + pageCount + " to " + doc.numberOfPages)
                }
                for ((pageIndex, expect) in expectations) {
                    if (pageIndex >= doc.numberOfPages) continue
                    val page = doc.getPage(pageIndex)
                    val instrs = parseInstrs(page)
                    val images = ContentScanner.imageSites(instrs, imagePredicate(page)).size
                    if (images != expect.images) {
                        problems.add("page " + (pageIndex + 1) + ": expected " + expect.images + " images, found " + images)
                    }
                    val shows = countShowOps(instrs)
                    if (shows < expect.showOps) {
                        problems.add("page " + (pageIndex + 1) + ": expected at least " + expect.showOps + " text operators, found " + shows)
                    }
                }
            }
        } catch (e: EditRefusedException) {
            problems.add("the written file could not be reopened: " + e.message)
        } catch (e: Exception) {
            problems.add("the written file could not be checked: " + e.javaClass.simpleName)
        }
        return problems
    }

    private fun countShowOps(instrs: List<Instr>): Int = instrs.count { it.op == "Tj" || it.op == "TJ" || it.op == "'" || it.op == "\"" }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun load(file: File): PDDocument {
        val doc = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw EditRefusedException(EditRefusedException.Reason.ENCRYPTED, "Encrypted PDFs are not supported")
        } catch (e: IOException) {
            throw EditRefusedException(EditRefusedException.Reason.UNREADABLE, "The PDF could not be read")
        }
        if (doc.isEncrypted) {
            doc.close()
            throw EditRefusedException(EditRefusedException.Reason.ENCRYPTED, "Encrypted PDFs are not supported")
        }
        return doc
    }

    private fun geometryOf(page: PDPage): PageGeometry {
        val box = page.cropBox
        val userUnit = page.cosObject.getFloat(COSName.getPDFName("UserUnit"), 1f)
        return PageGeometry(
            box.lowerLeftX.toDouble(), box.lowerLeftY.toDouble(), box.upperRightX.toDouble(), box.upperRightY.toDouble(),
            page.rotation, userUnit.toDouble()
        )
    }

    private fun imagePredicate(page: PDPage): (String) -> Boolean {
        val resources = page.resources
        return { name ->
            try {
                resources != null && resources.isImageXObject(COSName.getPDFName(name))
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun isSigned(doc: PDDocument): Boolean = try {
        doc.signatureDictionaries.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun isCertified(doc: PDDocument): Boolean = try {
        doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)?.containsKey(COSName.getPDFName("DocMDP")) == true
    } catch (e: Exception) {
        false
    }

    private fun validRect(r: RectPds): Boolean =
        r.left.isFinite() && r.bottom.isFinite() && r.right.isFinite() && r.top.isFinite() && r.width > 1e-6 && r.height > 1e-6
}
