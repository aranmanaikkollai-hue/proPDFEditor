// core/src/main/java/com/propdf/core/saf/SafEngine.kt
package com.propdf.core.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val contentResolver: ContentResolver = context.contentResolver

    companion object {
        private const val MAX_CACHE_SIZE_BYTES = 512L * 1024 * 1024 // 512MB
        private const val CHUNK_SIZE = 8192 // 8KB buffer
        private const val CACHE_DIR = "pdf_cache"
    }

    // ==================== URI RESOLUTION ====================

    suspend fun resolveToFile(uri: Uri): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return@withContext AppResult.Error(
                        AppException.FileNotFound("Empty file path")
                    ))
                    if (file.exists() && file.canRead()) {
                        AppResult.Success(file)
                    } else {
                        AppResult.Error(AppException.FileNotFound("File not readable: ${file.path}"))
                    }
                }
                "content" -> {
                    try {
                        // FIX: correct flag names are FLAG_GRANT_READ_URI_PERMISSION / FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        // Permission not persistable, continue with temporary access
                    }
                    copyUriToCache(uri)
                }
                else -> AppResult.Error(AppException.UnsupportedUri("Unsupported URI scheme: ${uri.scheme}"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to resolve URI: ${e.message}"))
        }
    }

    suspend fun openInputStream(uri: Uri): AppResult<InputStream> = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return@withContext AppResult.Error(
                        AppException.FileNotFound("Empty file path")
                    ))
                    AppResult.Success(file.inputStream())
                }
                "content" -> {
                    contentResolver.openInputStream(uri)?.let {
                        AppResult.Success(it)
                    } ?: AppResult.Error(AppException.FileNotFound("Cannot open content URI stream"))
                }
                else -> AppResult.Error(AppException.UnsupportedUri("Unsupported URI scheme"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to open stream: ${e.message}"))
        }
    }

    suspend fun openOutputStream(uri: Uri): AppResult<OutputStream> = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: return@withContext AppResult.Error(
                        AppException.FileNotFound("Empty file path")
                    ))
                    file.parentFile?.mkdirs()
                    AppResult.Success(file.outputStream())
                }
                "content" -> {
                    contentResolver.openOutputStream(uri)?.let {
                        AppResult.Success(it)
                    } ?: AppResult.Error(AppException.FileNotFound("Cannot open content URI for writing"))
                }
                else -> AppResult.Error(AppException.UnsupportedUri("Unsupported URI scheme"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to open output stream: ${e.message}"))
        }
    }

    suspend fun getDocumentInfo(uri: Uri): AppResult<DocumentInfo> = withContext(Dispatchers.IO) {
        try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: "")
                    AppResult.Success(DocumentInfo(
                        name = file.name,
                        size = file.length(),
                        mimeType = "application/pdf",
                        lastModified = file.lastModified()
                    ))
                }
                "content" -> {
                    val docFile = DocumentFile.fromSingleUri(context, uri)
                    if (docFile != null && docFile.exists()) {
                        AppResult.Success(DocumentInfo(
                            name = docFile.name ?: "unknown.pdf",
                            size = docFile.length(),
                            mimeType = docFile.type ?: "application/pdf",
                            lastModified = docFile.lastModified()
                        ))
                    } else {
                        queryDocumentInfoFallback(uri)
                    }
                }
                else -> AppResult.Error(AppException.UnsupportedUri("Unsupported scheme"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to get document info: ${e.message}"))
        }
    }

    fun isUriAccessible(uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "file" -> File(uri.path ?: "").exists()
                "content" -> contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
                else -> false
            }
        } catch (e: SecurityException) {
            false
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private suspend fun copyUriToCache(uri: Uri): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val info = when (val result = getDocumentInfo(uri)) {
                is AppResult.Success -> result.data
                is AppResult.Error -> return@withContext AppResult.Error(result.exception)
                else -> return@withContext AppResult.Error(AppException.Unknown("Unexpected result"))
            }

            if (info.size > MAX_CACHE_SIZE_BYTES) {
                return@withContext AppResult.Error(
                    AppException.FileTooLarge("File size ${info.size} exceeds max ${MAX_CACHE_SIZE_BYTES}")
                )
            }

            val safeName = sanitizeFileName(info.name)
            val cacheFile = File(context.cacheDir, "$CACHE_DIR/${safeName}")
            cacheFile.parentFile?.mkdirs()

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    var totalBytes = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalBytes > MAX_CACHE_SIZE_BYTES) {
                            cacheFile.delete()
                            return@withContext AppResult.Error(
                                AppException.FileTooLarge("File exceeds maximum cache size during copy")
                            )
                        }
                    }
                    output.flush()
                }
            } ?: return@withContext AppResult.Error(AppException.FileNotFound("Cannot open input stream"))

            if (cacheFile.exists() && cacheFile.length() > 0) {
                AppResult.Success(cacheFile)
            } else {
                AppResult.Error(AppException.FileNotFound("Cache file creation failed"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Cache copy failed: ${e.message}"))
        }
    }

    private fun queryDocumentInfoFallback(uri: Uri): AppResult<DocumentInfo> {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    AppResult.Success(DocumentInfo(
                        name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "unknown.pdf" else "unknown.pdf",
                        size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1,
                        mimeType = contentResolver.getType(uri) ?: "application/pdf",
                        lastModified = -1
                    ))
                } else {
                    AppResult.Error(AppException.FileNotFound("Cursor empty for URI"))
                }
            } ?: AppResult.Error(AppException.FileNotFound("Cannot query content resolver"))
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Query failed: ${e.message}"))
        }
    }

    private fun sanitizeFileName(name: String?): String {
        val base = name ?: "document_${System.currentTimeMillis()}.pdf"
        return base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    data class DocumentInfo(
        val name: String,
        val size: Long,
        val mimeType: String,
        val lastModified: Long
    )
}
