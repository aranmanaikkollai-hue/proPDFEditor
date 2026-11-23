package com.propdf.pdfedit.convert

import android.content.Context
import com.propdf.pdfedit.engine.BundledFonts
import com.propdf.pdfedit.ops.FontChoice
import com.propdf.pdfedit.ops.FontSource
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class TextToPdfOptions(
    /** A4 or LETTER (MATCH_IMAGE is treated as A4). Always portrait. */
    val pageMode: PageMode = PageMode.A4,
    val marginPts: Double = 54.0,
    val fontSizePt: Double = 11.0,
    /** Line height as a multiple of the font size; at least 1.25 so descenders stay inside the margin. */
    val lineSpacing: Double = 1.25,
    val font: FontChoice = FontChoice.BundledRegular,
    val title: String? = null
)

/**
 * Turns a text file into a PDF: UTF-8 or UTF-16 (BOM) or, failing that, Windows-1252; tabs become four spaces; lines are
 * wrapped at the margin by the real width of the font and split into pages. The font (bundled Liberation Sans, or a device
 * font) is embedded as a subset. Characters the font cannot draw become "?" and are reported. There is no shaping, so
 * Arabic, Indic and other complex scripts are not supported. The saved file is reopened and its page count checked before it
 * reaches the sink; on failure or cancellation the output is removed. The input is only read.
 */
class TextToPdfConverter(
    private val regularFont: FontSource,
    private val boldFont: FontSource
) {
    constructor(context: Context) : this(BundledFonts.regular(context), BundledFonts.bold(context))

    suspend fun convert(
        textFile: File,
        outputName: String,
        options: TextToPdfOptions,
        sink: OutputSink,
        tempDir: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ConvertReport = withContext(Dispatchers.IO) {
        val name = OutputNaming.baseName(outputName) + ".pdf"
        try {
            if (textFile.length() > MAX_BYTES) {
                throw ConvertException(ConvertException.Reason.UNREADABLE, "The text file is larger than 20 MB")
            }
            val decoded = try {
                TextDecoder.decode(textFile.readBytes())
            } catch (e: java.io.IOException) {
                throw ConvertException(ConvertException.Reason.UNREADABLE, "The text file could not be read", e)
            }
            val warnings = LinkedHashSet<ConvertWarning>()
            if (decoded.guessed) warnings.add(ConvertWarning.ENCODING_GUESSED)
            if (decoded.text.isBlank()) throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "The text file is empty")

            tempDir.mkdirs()
            var pageCount = 0
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)).use { doc ->
                val font = loadFont(doc, options.font, warnings)
                val clean = TextSanitizer.sanitize(decoded.text.replace("\t", "    ")) { s ->
                    try {
                        font.encode(s)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (clean.dropped.isNotEmpty()) warnings.add(ConvertWarning.CHARACTERS_REPLACED)
                if (clean.text.isBlank()) throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "Nothing in the file can be drawn")

                val size = options.fontSizePt.coerceIn(4.0, 72.0)
                val leading = size * options.lineSpacing.coerceAtLeast(1.25)
                val pageW = if (options.pageMode == PageMode.LETTER) ImagePagePlanner.LETTER_WIDTH else ImagePagePlanner.A4_WIDTH
                val pageH = if (options.pageMode == PageMode.LETTER) ImagePagePlanner.LETTER_HEIGHT else ImagePagePlanner.A4_HEIGHT
                val margin = options.marginPts.coerceIn(0.0, minOf(pageW, pageH) / 4.0)
                val widthOf: (String) -> Double = { s ->
                    try {
                        font.getStringWidth(s).toDouble() / 1000.0 * size
                    } catch (e: Exception) {
                        s.length * size * 0.6
                    }
                }
                val lines = TextLayout.wrap(clean.text, pageW - 2 * margin, widthOf)
                val linesPerPage = ((pageH - 2 * margin) / leading).toInt().coerceAtLeast(1)
                val pages = TextLayout.paginate(lines, linesPerPage)
                for ((n, pageLines) in pages.withIndex()) {
                    ensureActive()
                    val page = PDPage(PDRectangle(pageW.toFloat(), pageH.toFloat()))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(font, size.toFloat())
                        cs.newLineAtOffset(margin.toFloat(), (pageH - margin - size).toFloat())
                        var first = true
                        for (line in pageLines) {
                            if (!first) cs.newLineAtOffset(0f, -leading.toFloat())
                            first = false
                            if (line.isNotEmpty()) cs.showText(line)
                        }
                        cs.endText()
                    }
                    onProgress(n + 1, pages.size)
                }
                pageCount = pages.size
                val info = PDDocumentInformation()
                info.creator = "proPDFEditor"
                val title = options.title?.takeIf { it.isNotBlank() } ?: OutputNaming.baseName(textFile.name)
                info.title = title
                doc.documentInformation = info
                ensureActive()
                PdfPublisher.publish(doc, tempDir, pageCount, name, sink)
            }
            ConvertReport(listOf(name), pageCount, pageCount, warnings)
        } catch (e: CancellationException) {
            discard(sink, name)
            throw e
        } catch (e: ConvertException) {
            discard(sink, name)
            throw e
        } catch (e: OutOfMemoryError) {
            discard(sink, name)
            throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory", e)
        } catch (e: Exception) {
            discard(sink, name)
            throw ConvertException(ConvertException.Reason.WRITE_FAILED, "The PDF could not be created", e)
        }
    }

    private fun loadFont(doc: PDDocument, choice: FontChoice, warnings: MutableSet<ConvertWarning>): PDFont {
        fun loadFrom(source: FontSource): PDFont = source.open().use { PDType0Font.load(doc, it, true) }
        return when (choice) {
            FontChoice.BundledRegular -> loadFrom(regularFont)
            FontChoice.BundledBold -> loadFrom(boldFont)
            is FontChoice.DeviceFont -> try {
                loadFrom(choice.source)
            } catch (e: Exception) {
                warnings.add(ConvertWarning.FONT_UNAVAILABLE)
                loadFrom(regularFont)
            }
        }
    }

    private fun discard(sink: OutputSink, name: String) {
        try { sink.delete(name) } catch (e: Exception) { /* best effort */ }
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
