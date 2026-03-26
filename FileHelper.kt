package com.propdf.editor.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object FileHelper {

    data class SaveResult(
        val displayPath: String,
        val uri: Uri?,
        val file: File?
    )

    /** Save any file to public Downloads, auto-detecting MIME type. */
    fun saveToDownloads(context: Context, sourceFile: File): SaveResult {
        val mimeType = mimeFor(sourceFile.name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, sourceFile, mimeType)
            } else {
                saveToDownloadsLegacy(sourceFile)
            }
        } catch (e: Exception) {
            saveToAppFolder(context, sourceFile)
        }
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".pdf",  true) -> "application/pdf"
        name.endsWith(".jpg",  true) -> "image/jpeg"
        name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png",  true) -> "image/png"
        name.endsWith(".txt",  true) -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun saveViaMediaStore(
        context: Context, source: File, mimeType: String
    ): SaveResult {
        val resolver = context.contentResolver
        val values   = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw Exception("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return SaveResult("Downloads/${source.name}", uri, null)
    }

    private fun saveToDownloadsLegacy(source: File): SaveResult {
        val dir  = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        dir.mkdirs()
        val dest = File(dir, source.name)
        source.copyTo(dest, overwrite = true)
        return SaveResult(dest.absolutePath, Uri.fromFile(dest), dest)
    }

    private fun saveToAppFolder(context: Context, source: File): SaveResult {
        val dir  = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val dest = File(dir, source.name)
        source.copyTo(dest, overwrite = true)
        return SaveResult(
            "Android/data/com.propdf.editor/files/${source.name}",
            Uri.fromFile(dest), dest
        )
    }

    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return null)
                return if (f.exists() && f.length() > 0) f else null
            }
            val name = getFileName(context, uri)
                ?: "pdf_${System.currentTimeMillis()}.pdf"
            val dest = File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192); var n: Int
                    while (input.read(buf).also { n = it } >= 0)
                        output.write(buf, 0, n)
                    output.flush()
                }
            } ?: return null
            if (dest.exists() && dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    fun getFileName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) { uri.lastPathSegment }

    fun tempFile(context: Context, prefix: String, ext: String = "pdf"): File {
        val name = "${prefix}_${System.currentTimeMillis()}.$ext"
        return File(context.cacheDir, name)
    }
}
