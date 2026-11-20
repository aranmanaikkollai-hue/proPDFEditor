package com.propdf.pdfedit.convert

import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Image file types a PDF page can be converted to. */
enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg")
}

/** Which pages to convert. */
sealed interface PageSelection {
    object All : PageSelection

    /** A 1-based range list such as "1-3,5,8-" (see [PageRanges]). */
    class Ranges(val spec: String) : PageSelection
}

enum class ConvertWarning {
    /** At least one page was rendered at a lower resolution than requested to stay within the pixel limit. */
    RESOLUTION_REDUCED,

    /** Every selected page had no extractable text (for example a scanned document); OCR would be needed. */
    NO_TEXT_LAYER,

    /** Some selected pages had no extractable text. */
    SOME_PAGES_WITHOUT_TEXT
}

class ConvertReport(
    /** Names of the files written through the sink, in order. */
    val outputs: List<String>,
    val pagesConverted: Int,
    val totalPages: Int,
    val warnings: Set<ConvertWarning>,
    /** Lowest effective resolution used (images only), 0 for text. */
    val minEffectiveDpi: Double = 0.0,
    /** Number of selected pages without extractable text (text only). */
    val pagesWithoutText: Int = 0
)

class ConvertException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        PASSWORD_REQUIRED,
        WRONG_PASSWORD,

        /** The file's permissions forbid extracting text (an owner password would be needed). */
        NOT_PERMITTED,
        UNREADABLE,
        INVALID_RANGE,
        EMPTY_SELECTION,
        OUT_OF_MEMORY,
        WRITE_FAILED
    }
}

/**
 * Where converted files go. The app adapts this to a Storage Access Framework folder; tests use [DirectorySink].
 * [delete] must tolerate names that were never created.
 */
interface OutputSink {
    fun open(name: String, mimeType: String): OutputStream
    fun delete(name: String)
}

/** Writes into a plain directory; used by tests and by internal temporary output. */
class DirectorySink(private val dir: File) : OutputSink {
    init {
        dir.mkdirs()
    }

    override fun open(name: String, mimeType: String): OutputStream {
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Unsafe output name")
        return file.outputStream()
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}
