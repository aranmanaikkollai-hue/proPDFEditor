package com.propdf.editor.data.cloud.dropbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderResult
import com.dropbox.core.v2.files.Metadata
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dropbox integration manager.
 * Uses Dropbox Core SDK for authentication and file operations.
 */
@Singleton
class DropboxManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val APP_KEY = "YOUR_DROPBOX_APP_KEY" // Replace with actual key
        private const val PDF_EXTENSION = ".pdf"
    }

    private val prefs = context.getSharedPreferences("dropbox_prefs", Context.MODE_PRIVATE)
    private var client: DbxClientV2? = null

    private fun getClient(): DbxClientV2? {
        val accessToken = prefs.getString("access_token", null)
        return if (accessToken != null) {
            val config = DbxRequestConfig.newBuilder("ProPDFEditor/2.0")
                .withUserLocaleFrom(Locale.getDefault())
                .build()
            DbxClientV2(config, accessToken)
        } else null
    }

    fun startAuthentication() {
        Auth.startOAuth2PKCE(context, APP_KEY)
    }

    fun finishAuthentication() {
        val token = Auth.getOAuth2Token()
        if (token != null) {
            prefs.edit().putString("access_token", token).apply()
            client = getClient()
        }
    }

    fun isAuthenticated(): Boolean {
        return prefs.getString("access_token", null) != null
    }

    fun signOut() {
        prefs.edit().remove("access_token").apply()
        client = null
    }

    /**
     * List PDF files from Dropbox.
     */
    suspend fun listPdfFiles(path: String = ""): List<PdfDocument> = withContext(Dispatchers.IO) {
        val dbxClient = client ?: getClient() ?: return@withContext emptyList()

        try {
            val result: ListFolderResult = dbxClient.files().listFolder(path.ifEmpty { "" })
            result.entries
                .filter { it.name.endsWith(PDF_EXTENSION, ignoreCase = true) }
                .map { metadata ->
                    val fileMeta = metadata as? FileMetadata
                    PdfDocument(
                        uri = Uri.parse("dropbox://$path/${metadata.name}"),
                        fileName = metadata.name,
                        displayName = metadata.name,
                        fileSize = fileMeta?.size ?: 0,
                        lastModified = fileMeta?.clientModified?.time ?: 0,
                        cloudProvider = CloudProvider.DROPBOX,
                        cloudId = metadata.pathLower,
                        syncStatus = SyncStatus.SYNCED
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Upload a file to Dropbox.
     */
    suspend fun uploadFile(inputStream: InputStream, path: String): String? = withContext(Dispatchers.IO) {
        val dbxClient = client ?: getClient() ?: return@withContext null

        try {
            val metadata = dbxClient.files()
                .uploadBuilder(path)
                .withAutorename(true)
                .uploadAndFinish(inputStream)
            metadata.id
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Download a file from Dropbox.
     */
    suspend fun downloadFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val dbxClient = client ?: getClient() ?: return@withContext null

        try {
            val outputStream = ByteArrayOutputStream()
            dbxClient.files().download(path).download(outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete a file from Dropbox.
     */
    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val dbxClient = client ?: getClient() ?: return@withContext false

        try {
            dbxClient.files().deleteV2(path)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get a temporary shared link for a file.
     */
    suspend fun getSharedLink(path: String): String? = withContext(Dispatchers.IO) {
        val dbxClient = client ?: getClient() ?: return@withContext null

        try {
            val result = dbxClient.sharing().createSharedLinkWithSettings(path)
            result.url
        } catch (e: Exception) {
            null
        }
    }
}
