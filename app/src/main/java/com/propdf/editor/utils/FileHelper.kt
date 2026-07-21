package com.propdf.editor.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility class for file operations with proper error handling
 * and memory-efficient streaming.
 */
object FileHelper {

    data class SaveResult(
        val displayPath: String,
        val uri: Uri,
        val file: File? = null
    )

    fun getFileName(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else null
                }
            }
            "file" -> File(uri.path ?: "").name
            else -> uri.lastPathSegment
        }
    }

    fun isPdf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                header.contentEquals("%PDF".toByteArray())
            }
        } catch (_: Exception) { false }
    }

    fun isValidPdfUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(4)
                stream.read(header)
                header.contentEquals("%PDF".toByteArray())
            } ?: false
        } catch (_: Exception) { false }
    }

    fun tempFile(context: Context, name: String): File {
        return File(context.cacheDir, "pdf_${System.currentTimeMillis()}_$name.pdf")
    }

    fun saveToDownloads(context: Context, file: File): SaveResult {
        val displayName = file.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ProPDF")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return SaveResult("failed", Uri.EMPTY)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            SaveResult("Downloads/ProPDF/$displayName", uri)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF")
            dir.mkdirs()
            val dest = File(dir, displayName)
            file.copyTo(dest, overwrite = true)
            SaveResult(dest.absolutePath, Uri.fromFile(dest), dest)
        }
    }
}
