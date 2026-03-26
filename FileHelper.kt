package com.propdf.editor.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * FileHelper -- handles file storage across ALL Android versions.
 *
 * Android storage model by version:
 *   API 21-28: WRITE_EXTERNAL_STORAGE -> files go to Downloads or SD card directly
 *   API 29   : Scoped storage introduced -- must use MediaStore or app-specific dirs
 *   API 30+  : Scoped storage enforced -- cannot write to arbitrary paths
 *
 * Strategy:
 *   - API 29+: Save to MediaStore Downloads (shows in Files app, any file manager)
 *   - API <29: Save to Environment.DIRECTORY_DOWNLOADS directly
 *   - Fallback: App-specific external dir (always works, no permission needed)
 *
 * Output files appear in:
 *   - Files app -> Downloads -> ProPDF_output.pdf
 *   - Any file manager under Downloads
 */
object FileHelper {

    /**
     * Save a File to the public Downloads folder.
     * Returns the final saved File (or Uri on API 29+).
     * Always shows in the Files app and file managers.
     */
    fun saveToDownloads(context: Context, sourceFile: File): SaveResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ -- use MediaStore
                saveViaMediaStore(context, sourceFile)
            } else {
                // API 21-28 -- write directly to Downloads folder
                saveToDownloadsLegacy(sourceFile)
            }
        } catch (e: Exception) {
            // Final fallback -- app-specific external storage
            saveToAppFolder(context, sourceFile)
        }
    }

    private fun saveViaMediaStore(context: Context, source: File): SaveResult {
        val resolver = context.contentResolver
        val values   = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, values) ?: throw Exception("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }

        // Mark as complete
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return SaveResult(
            displayPath = "Downloads/${source.name}",
            uri = uri,
            file = null
        )
    }

    private fun saveToDownloadsLegacy(source: File): SaveResult {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        downloadsDir.mkdirs()

        val dest = File(downloadsDir, source.name)
        source.copyTo(dest, overwrite = true)

        return SaveResult(
            displayPath = dest.absolutePath,
            uri = Uri.fromFile(dest),
            file = dest
        )
    }

    private fun saveToAppFolder(context: Context, source: File): SaveResult {
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val dest = File(dir, source.name)
        source.copyTo(dest, overwrite = true)
        return SaveResult(
            displayPath = "Android/data/com.propdf.editor/files/${source.name}",
            uri = Uri.fromFile(dest),
            file = dest
        )
    }

    /**
     * Copy a URI (content:// or file://) into app cache.
     * Returns a real File that PdfRenderer / PDFBox can open.
     */
    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return null)
                return if (f.exists() && f.length() > 0) f else null
            }

            val name = getFileName(context, uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
            val dest = File(context.cacheDir, name)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            } ?: return null

            if (dest.exists() && dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && col >= 0) c.getString(col) else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) { uri.lastPathSegment }
    }

    /** Get a temp output file in cache for processing. */
    fun tempFile(context: Context, prefix: String): File {
        val name = "${prefix}_${System.currentTimeMillis()}.pdf"
        return File(context.cacheDir, name)
    }

    data class SaveResult(
        val displayPath: String,
        val uri: Uri?,
        val file: File?
    )
}
