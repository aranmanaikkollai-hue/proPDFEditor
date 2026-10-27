package com.propdf.core.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * The only way the app turns a private file into a `content://` URI that another app (or another
 * activity, e.g. the crop screen) may read.
 *
 * Why: the app's FileProvider used to expose whole directories (`files/`, `cache/`, the external
 * app directories and the entire shared storage root). Anything under them, including imported signing
 * certificates (`files/keystores/`) and decrypted working copies (`cache/viewer_unlocked/`), could be
 * handed out if some code path built a URI for it. Now the provider exposes only
 * `cache/shared/` (plus the narrow scanner and conversion output folders listed in
 * `res/xml/file_paths.xml`), and everything that must be shared is COPIED here first:
 *
 * ```
 * cache/shared/<random uuid>/<sanitised display name>
 * ```
 *
 * Each share gets its own random subdirectory, so file names never collide and a content URI is not
 * guessable from a name. Staged copies are removed by [purgeOlderThan] (called at application start).
 *
 * Limits, stated plainly: this is not secure erasure (deleting a cache file does not wipe flash), a
 * recipient that was already granted a URI can read the copy until it is purged, and the copy costs
 * time and space for large files (callers should run [stageFile] off the main thread).
 */
object ShareStaging {

    const val DIR_NAME = "shared"

    /** Staged copies older than this are removed at startup. Recipients usually read within seconds. */
    const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private const val MAX_NAME_LENGTH = 120

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun rootDir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /** A path for a NEW file inside a fresh random subdirectory. The caller writes the bytes. */
    fun newFile(context: Context, displayName: String): File {
        val dir = File(rootDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, sanitizeName(displayName))
    }

    /** Content URI for a file that was created through [newFile] or [stageFile]; anything else is refused. */
    fun uriFor(context: Context, file: File): Uri {
        require(isInside(rootDir(context), file)) { "Only files staged for sharing can be shared." }
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /**
     * Copies [source] (any readable file) into the staging area and returns a content URI for the COPY.
     * @throws IllegalArgumentException if [source] is not a readable file.
     */
    fun stageFile(context: Context, source: File, displayName: String = source.name): Uri {
        require(source.isFile && source.canRead()) { "The file to share is missing or unreadable." }
        val target = newFile(context, displayName)
        try {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } catch (e: Exception) {
            target.parentFile?.deleteRecursively() // never leave a partial copy behind
            throw e
        }
        return uriFor(context, target)
    }

    /** Removes staged entries last modified before `now - maxAgeMs`. Returns how many entries were removed. */
    fun purgeOlderThan(
        context: Context,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        now: Long = System.currentTimeMillis()
    ): Int = purgeDirectory(File(context.cacheDir, DIR_NAME), now - maxAgeMs)

    // ---- pure helpers (JVM-testable) ------------------------------------------------------------

    /** Deletes direct children of [root] whose last-modified time is strictly before [cutoffMillis]. */
    internal fun purgeDirectory(root: File, cutoffMillis: Long): Int {
        val entries = root.listFiles() ?: return 0
        var removed = 0
        for (entry in entries) {
            if (entry.lastModified() >= cutoffMillis) continue
            val ok = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (ok) removed++
        }
        return removed
    }

    /** True if [file] resolves (after `..` and symlinks) to a path strictly inside [root]. */
    internal fun isInside(root: File, file: File): Boolean {
        val rootPath = try { root.canonicalPath } catch (e: Exception) { return false }
        val filePath = try { file.canonicalPath } catch (e: Exception) { return false }
        return filePath.startsWith(rootPath + File.separator)
    }

    /** Keeps the visible file name but removes anything that could escape the directory or confuse a reader. */
    internal fun sanitizeName(name: String): String {
        val cleaned = name
            .map { if (it == '/' || it == '\\' || it.code < 32 || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|') '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')
        val shortened = if (cleaned.length > MAX_NAME_LENGTH) {
            val dot = cleaned.lastIndexOf('.')
            if (dot in 1 until cleaned.length && cleaned.length - dot <= 10) {
                cleaned.substring(0, MAX_NAME_LENGTH - (cleaned.length - dot)) + cleaned.substring(dot)
            } else {
                cleaned.substring(0, MAX_NAME_LENGTH)
            }
        } else {
            cleaned
        }
        return shortened.ifBlank { "file" }
    }
}
