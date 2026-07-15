package com.propdf.editor.utils

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.propdf.editor.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat

object FileUtils {

    private const val BUFFER_SIZE = 8192
    private const val CONVERSION_DIR = "conversions"

    fun getConversionOutputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), CONVERSION_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, CONVERSION_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    fun getUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) {
                        size = cursor.getLong(index)
                    }
                }
            }
        }
        return size
    }

    fun getFormattedFileSize(context: Context, uri: Uri): String {
        val size = getFileSize(context, uri)
        return formatFileSize(size)
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(
            size / Math.pow(1024.0, digitGroups.toDouble())
        ) + " " + units[digitGroups.coerceAtMost(units.size - 1)]
    }

    fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("temp_", "_input", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
            tempFile
        } catch (e: IOException) {
            null
        }
    }

    fun isImage(uri: Uri): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()
        return extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff")
    }

    fun isPdf(uri: Uri): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase()
        return extension == "pdf"
    }

    fun getFileIcon(uri: Uri): Int {
        return when {
            isPdf(uri) -> R.drawable.ic_pdf
            isImage(uri) -> R.drawable.ic_image
            else -> R.drawable.ic_file
        }
    }

    fun openFile(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        val chooser = Intent.createChooser(intent, context.getString(R.string.open_with))
        context.startActivity(chooser)
    }

    fun shareFile(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = getMimeType(uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    private fun getMimeType(uri: Uri): String {
        return when {
            isPdf(uri) -> "application/pdf"
            isImage(uri) -> "image/*"
            else -> "*/*"
        }
    }

    fun cleanupTempFiles(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_")) {
                file.delete()
            }
        }
    }

    fun generateUniqueFileName(baseName: String, extension: String, outputDir: File): String {
        var counter = 0
        var fileName = "$baseName.$extension"
        while (File(outputDir, fileName).exists()) {
            counter++
            fileName = "${baseName}_$counter.$extension"
        }
        return fileName
    }
}
