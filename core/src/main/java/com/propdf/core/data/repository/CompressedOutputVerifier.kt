package com.propdf.core.data.repository

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** A compression result that failed its own safety checks. The message is written for the user. */
class CompressionOutputException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads a freshly written PDF back from its destination (file:// or content://) and proves that it is
 * usable: it opens as a PDF, has the expected page count and holds exactly the bytes that were written.
 *
 * It does not modify anything. Blocking: call it from an IO dispatcher.
 */
object CompressedOutputVerifier {

    /** Returns the number of bytes read from the destination (its real size) or throws [CompressionOutputException]. */
    fun verify(context: Context, uri: Uri, expectedPages: Int, expectedBytes: Long): Long {
        val raw: InputStream = try {
            when (uri.scheme) {
                "file" -> FileInputStream(File(requireNotNull(uri.path) { "missing path" }))
                "content" -> context.contentResolver.openInputStream(uri)
                    ?: throw IOException("provider returned no stream")
                else -> throw IOException("unsupported location")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw CompressionOutputException(
                "The saved file could not be read back to verify it. Please try a different location.", e
            )
        }

        return CountingInputStream(raw).use { stream ->
            try {
                PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { doc ->
                    if (doc.numberOfPages < 1 || doc.numberOfPages != expectedPages) {
                        throw CompressionOutputException(
                            "The saved file has the wrong number of pages. Your original PDF was not changed."
                        )
                    }
                }
            } catch (e: CompressionOutputException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw CompressionOutputException(
                    "The saved file is not a readable PDF. Your original PDF was not changed.", e
                )
            }
            val sink = ByteArray(8192)
            while (stream.read(sink) >= 0) {
                // drain: count every byte that is really at the destination
            }
            if (stream.count != expectedBytes) {
                throw CompressionOutputException(
                    "The saved file is incomplete (${stream.count} of $expectedBytes bytes). Your original PDF was not changed."
                )
            }
            stream.count
        }
    }

    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }

        override fun markSupported(): Boolean = false
    }
}
