package com.propdf.editor.ui

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileNotFoundException

/**
 * Replaces the bytes behind a document [Uri] (file or `content://`) with a validated new PDF and
 * restores the original if anything goes wrong.
 *
 * Order of operations (the original is not touched until step 4):
 *  1. stage the new content to a private cache file;
 *  2. validate it ([PdfResultValidator]: non-empty, loadable, expected page count);
 *  3. back up the original to a private cache file;
 *  4. write the new content over the target (truncating);
 *  5. read the target back and compare its size; on any failure restore the backup.
 *
 * Honest limits: this is **not atomic**. A `content://` provider offers no atomic replace, so if the
 * process dies between steps 4 and 5 the target can be left truncated; the backup then exists only
 * in the app cache. If the automatic restore itself fails the backup file is **kept** and reported.
 * Cache files are private to the app and excluded from Auto Backup; deletion is not secure erasure.
 */
object SafeDocumentReplacer {

    sealed class Outcome {
        /** Target now holds the new content. */
        object Replaced : Outcome()

        /**
         * Target was not replaced. [originalIntact] is true when the original content is known to be
         * in place (nothing was written, or the restore succeeded). [backup] is non-null only when the
         * restore failed and the backup file was kept.
         */
        data class Failed(
            val message: String,
            val originalIntact: Boolean,
            val backup: File? = null
        ) : Outcome()
    }

    fun replace(
        context: Context,
        target: Uri,
        newContent: Uri,
        validate: (File) -> String?
    ): Outcome {
        val stamp = System.nanoTime()
        val staged = File(context.cacheDir, "replace_new_$stamp.pdf")
        val backup = File(context.cacheDir, "replace_backup_$stamp.pdf")
        var keepBackup = false
        try {
            // 1. stage
            try {
                copyUriToFile(context, newContent, staged)
            } catch (e: Exception) {
                return Outcome.Failed("The new document could not be read; the original was not changed.", true)
            }

            // 2. validate
            validate(staged)?.let { problem ->
                return Outcome.Failed("$problem The original was not changed.", true)
            }

            // 3. back up the original
            try {
                copyUriToFile(context, target, backup)
            } catch (e: Exception) {
                return Outcome.Failed("The original could not be backed up, so it was not changed.", true)
            }

            // 4. + 5. write, then verify; restore on failure
            try {
                writeFileToUri(context, staged, target)
                val written = sizeOfUri(context, target)
                if (written != staged.length()) {
                    throw IllegalStateException("size mismatch after write")
                }
                return Outcome.Replaced
            } catch (e: Exception) {
                // If nothing reached the target (for example it could not be opened for writing) the
                // original is still there and no restore is needed.
                if (sameContent(context, target, backup)) {
                    return Outcome.Failed("Saving failed. The original document is unchanged.", true)
                }
                val restored = try {
                    writeFileToUri(context, backup, target)
                    sizeOfUri(context, target) == backup.length()
                } catch (restoreError: Exception) {
                    false
                }
                return if (restored) {
                    Outcome.Failed("Saving failed. The original document was restored.", true)
                } else {
                    keepBackup = true
                    Outcome.Failed(
                        "Saving failed and the original could not be restored automatically. " +
                            "A backup copy was kept in the app's cache.",
                        false,
                        backup
                    )
                }
            }
        } finally {
            staged.delete()
            if (!keepBackup) backup.delete()
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use { source -> out.outputStream().use { sink -> source.copyTo(sink) } }
        if (out.length() == 0L) throw IllegalStateException("empty")
    }

    private fun writeFileToUri(context: Context, file: File, uri: Uri) {
        val resolver = context.contentResolver
        val opened = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            // Some providers reject the truncate mode; the size check afterwards catches a short write.
            resolver.openOutputStream(uri)
        }
        val stream = opened ?: throw FileNotFoundException(uri.toString())
        stream.use { sink ->
            file.inputStream().use { source -> source.copyTo(sink) }
            sink.flush()
        }
    }

    /** True if the bytes behind [uri] equal [file]; any read problem counts as "not the same". */
    private fun sameContent(context: Context, uri: Uri, file: File): Boolean = try {
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            false
        } else {
            input.use { remote ->
                file.inputStream().use { local ->
                    val a = ByteArray(64 * 1024)
                    val b = ByteArray(64 * 1024)
                    var same = true
                    while (same) {
                        val na = remote.readFully(a)
                        val nb = local.readFully(b)
                        if (na != nb || !a.copyOf(na).contentEquals(b.copyOf(nb))) same = false
                        if (na <= 0 || nb <= 0) break
                    }
                    same
                }
            }
        }
    } catch (e: Exception) {
        false
    }

    /** Reads until [buffer] is full or the stream ends; returns the number of bytes read (0 at end). */
    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun sizeOfUri(context: Context, uri: Uri): Long {
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        return input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        }
    }
}

/** Checks a PDF produced by an operation before it is allowed to replace a user's document. */
object PdfResultValidator {

    /**
     * @return null if [file] is acceptable, otherwise a user-presentable reason.
     * @param expectedPages if non-null the page count must match exactly.
     */
    fun validate(file: File, expectedPages: Int?): String? {
        if (!file.exists() || file.length() == 0L) return "The result is empty."
        return try {
            PDDocument.load(file).use { doc ->
                val pages = doc.numberOfPages
                when {
                    pages <= 0 -> "The result has no pages."
                    expectedPages != null && pages != expectedPages ->
                        "The result has $pages page(s) but $expectedPages were expected."
                    else -> null
                }
            }
        } catch (e: Exception) {
            "The result is not a readable PDF."
        }
    }
}
