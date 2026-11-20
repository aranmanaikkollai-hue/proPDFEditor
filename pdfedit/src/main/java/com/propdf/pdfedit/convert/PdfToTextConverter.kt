package com.propdf.pdfedit.convert

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class PageSeparator {
    NONE,

    /** A form feed character after each page, the convention of pdftotext. */
    FORM_FEED,

    /** A line "--- Page N ---" before each page. */
    HEADER_LINE
}

class PdfToTextOptions(
    val pages: PageSelection = PageSelection.All,
    val separator: PageSeparator = PageSeparator.FORM_FEED,
    /** Orders text by position on the page instead of the order it is drawn in. */
    val sortByPosition: Boolean = false
)

/**
 * Extracts the text layer of a PDF into one UTF-8 text file with PDFBox. It does not run OCR: pages without a text
 * layer give empty text and a warning. A file whose permissions forbid text extraction is refused (NOT_PERMITTED).
 * On failure or cancellation the output file is deleted through the sink. The input file is only read.
 */
class PdfToTextConverter {

    suspend fun convert(
        pdf: File,
        baseName: String,
        options: PdfToTextOptions,
        sink: OutputSink,
        password: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ConvertReport = withContext(Dispatchers.IO) {
        var outputName: String? = null
        try {
            val document = try {
                PDDocument.load(pdf, password ?: "")
            } catch (e: InvalidPasswordException) {
                val reason = if (password.isNullOrEmpty()) ConvertException.Reason.PASSWORD_REQUIRED else ConvertException.Reason.WRONG_PASSWORD
                throw ConvertException(reason, if (password.isNullOrEmpty()) "This PDF needs a password" else "The password is not correct", e)
            } catch (e: IOException) {
                throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF could not be read", e)
            }
            document.use { doc ->
                if (!doc.currentAccessPermission.canExtractContent()) {
                    throw ConvertException(ConvertException.Reason.NOT_PERMITTED, "This PDF does not allow text to be extracted")
                }
                val total = doc.numberOfPages
                if (total <= 0) throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF has no pages")
                val indices = when (val sel = options.pages) {
                    PageSelection.All -> (0 until total).toList()
                    is PageSelection.Ranges -> when (val parsed = PageRanges.parse(sel.spec, total)) {
                        is PageRanges.Result.Ok -> parsed.indices
                        is PageRanges.Result.Error -> throw ConvertException(ConvertException.Reason.INVALID_RANGE, parsed.message)
                    }
                }
                if (indices.isEmpty()) throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "No pages selected")

                val name = OutputNaming.textName(OutputNaming.baseName(baseName))
                var withoutText = 0
                sink.open(name, "text/plain").bufferedWriter(Charsets.UTF_8).use { writer ->
                    outputName = name
                    for ((n, pageIndex) in indices.withIndex()) {
                        ensureActive()
                        val stripper = PDFTextStripper()
                        stripper.startPage = pageIndex + 1
                        stripper.endPage = pageIndex + 1
                        stripper.sortByPosition = options.sortByPosition
                        stripper.lineSeparator = "\n"
                        val text = stripper.getText(doc)
                        if (text.isBlank()) withoutText++
                        if (options.separator == PageSeparator.HEADER_LINE) {
                            writer.write("--- Page " + (pageIndex + 1) + " ---\n")
                        }
                        writer.write(text)
                        if (options.separator == PageSeparator.FORM_FEED) writer.write("\u000C")
                        onProgress(n + 1, indices.size)
                    }
                }
                val warnings = LinkedHashSet<ConvertWarning>()
                if (withoutText == indices.size) warnings.add(ConvertWarning.NO_TEXT_LAYER)
                else if (withoutText > 0) warnings.add(ConvertWarning.SOME_PAGES_WITHOUT_TEXT)
                ConvertReport(listOf(name), indices.size, total, warnings, 0.0, withoutText)
            }
        } catch (e: CancellationException) {
            outputName?.let { discard(sink, it) }
            throw e
        } catch (e: ConvertException) {
            outputName?.let { discard(sink, it) }
            throw e
        } catch (e: OutOfMemoryError) {
            outputName?.let { discard(sink, it) }
            throw ConvertException(ConvertException.Reason.OUT_OF_MEMORY, "Not enough memory", e)
        } catch (e: Exception) {
            outputName?.let { discard(sink, it) }
            throw ConvertException(ConvertException.Reason.WRITE_FAILED, "The text could not be extracted", e)
        }
    }

    private fun discard(sink: OutputSink, name: String) {
        try { sink.delete(name) } catch (e: Exception) { /* best effort */ }
    }
}
