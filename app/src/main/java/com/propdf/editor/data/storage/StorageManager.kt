package com.propdf.editor.data.storage

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.propdf.editor.domain.model.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file storage using Storage Access Framework (SAF),
 * scoped storage compliance, and persistent URI permissions.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }

    private val AUTHORITY = context.packageName + ".fileprovider"

    private val contentResolver: ContentResolver = context.contentResolver
    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    /**
     * Request persistent access to a directory via SAF.
     * Call this with ActivityResultContracts.OpenDocumentTree()
     */
    fun persistUriPermission(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)
        savePersistedUri(uri.toString())
    }

    /**
     * Get all persisted URI permissions.
     */
    fun getPersistedPermissions(): List<UriPermission> {
        return contentResolver.persistedUriPermissions
    }

    /**
     * Release a persisted URI permission.
     */
    fun releaseUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.releasePersistableUriPermission(uri, flags)
        removePersistedUri(uri.toString())
    }

    /**
     * Scan a directory for PDF files and return document info.
     */
    suspend fun scanDirectory(uri: Uri): List<PdfDocument> = withContext(Dispatchers.IO) {
        val documents = mutableListOf<PdfDocument>()
        val treeUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri)
        )

        val cursor = contentResolver.query(
            treeUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val mimeType = it.getString(4) ?: ""
                if (mimeType == PDF_MIME_TYPE || mimeType == "application/octet-stream") {
                    val docId = it.getString(0)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                    documents.add(
                        PdfDocument(
                            id = 0,
                            uri = docUri,
                            displayName = it.getString(1) ?: "Unknown",
                            fileSize = it.getLong(2),
                            dateModified = it.getLong(3),
                            dateAdded = it.getLong(3)
                        )
                    )
                }
            }
        }
        documents
    }

    /**
     * Copy a file from SAF URI to app-private cache.
     */
    suspend fun copyToCache(uri: Uri, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "pdf_temp").apply { mkdirs() }
            val outFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get file metadata from a URI.
     */
    suspend fun getFileInfo(uri: Uri): PdfDocument? = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    PdfDocument(
                        id = 0,
                        uri = uri,
                        displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown",
                        fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0,
                        dateModified = System.currentTimeMillis(),
                        dateAdded = System.currentTimeMillis()
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a shareable URI using FileProvider.
     */
    fun getShareableUri(file: File): Uri {
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * Check if we have permission for a URI.
     */
    fun hasPermission(uri: Uri): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get app-private storage directory for internal files.
     */
    fun getInternalStorageDir(): File {
        return File(context.filesDir, "pdfs").apply { mkdirs() }
    }

    /**
     * Delete a file from internal storage.
     */
    suspend fun deleteInternalFile(fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(getInternalStorageDir(), fileName).delete()
    }

    /**
     * Get total size of internal PDF storage.
     */
    suspend fun getInternalStorageSize(): Long = withContext(Dispatchers.IO) {
        getInternalStorageDir().walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    // === Persistent URI persistence ===

    private fun savePersistedUri(uriString: String) {
        val uris = getSavedUris().toMutableSet()
        uris.add(uriString)
        prefs.edit().putStringSet("persisted_uris", uris).apply()
    }

    private fun removePersistedUri(uriString: String) {
        val uris = getSavedUris().toMutableSet()
        uris.remove(uriString)
        prefs.edit().putStringSet("persisted_uris", uris).apply()
    }

    fun getSavedUris(): Set<String> {
        return prefs.getStringSet("persisted_uris", emptySet()) ?: emptySet()
    }

    /**
     * Clean up expired permissions (Android may revoke them).
     */
    fun cleanupExpiredPermissions() {
        val current = getPersistedPermissions().map { it.uri.toString() }.toSet()
        val saved = getSavedUris()
        val expired = saved - current
        expired.forEach { removePersistedUri(it) }
    }
}
