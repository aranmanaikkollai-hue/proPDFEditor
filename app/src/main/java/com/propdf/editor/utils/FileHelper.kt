package com.propdf.editor.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object FileHelper {

    data class SaveResult(
        val displayPath: String,
        val uri: Uri,
        val file: File
    )

    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return null)
                return if (f.exists() && f.length() > 0) f else null
            }
            val rawName = getFileName(context, uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
            val safeName = sanitizeFileName(rawName)
            val dest = File(context.cacheDir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
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

    fun saveToDownloads(context: Context, source: File): SaveResult {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF")
        dir.mkdirs()
        val dest = File(dir, source.name)
        source.copyTo(dest, overwrite = true)
        return SaveResult(dest.absolutePath, Uri.fromFile(dest), dest)
    }

    fun isPdf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
            }
        } catch (_: Exception) { false }
    }

    private fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }
}
