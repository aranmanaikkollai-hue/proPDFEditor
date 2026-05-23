package com.propdf.editor.data.cloud.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.propdf.editor.R
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Drive integration manager.
 * Handles authentication, file listing, upload, download, and sync.
 */
@Singleton
class GoogleDriveManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val PDF_MIME = "application/pdf"
    }

    private val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    fun signOut() {
        signInClient.signOut()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("ProPDF Editor")
            .build()
    }

    /**
     * List PDF files from Google Drive.
     */
    suspend fun listPdfFiles(): List<PdfDocument> = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext emptyList()

        try {
            val drive = getDriveService(account)
            val result = drive.files().list()
                .setQ("mimeType = '$PDF_MIME' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name, size, modifiedTime, thumbnailLink)")
                .setPageSize(100)
                .execute()

            result.files.map { file ->
                PdfDocument(
                    uri = Uri.parse("https://drive.google.com/file/d/${file.id}/view"),
                    fileName = file.name,
                    displayName = file.name,
                    fileSize = file.getSize() ?: 0,
                    lastModified = file.modifiedTime?.value ?: 0,
                    cloudProvider = CloudProvider.GOOGLE_DRIVE,
                    cloudId = file.id,
                    syncStatus = SyncStatus.SYNCED
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Upload a PDF file to Google Drive.
     */
    suspend fun uploadFile(uri: Uri, fileName: String): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext null

        try {
            val drive = getDriveService(account)
            val metadata = File().apply {
                name = fileName
                mimeType = PDF_MIME
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val mediaContent = com.google.api.client.http.InputStreamContent(PDF_MIME, stream)
                val file = drive.files().create(metadata, mediaContent)
                    .setFields("id")
                    .execute()
                file.id
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Download a PDF file from Google Drive.
     */
    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext null

        try {
            val drive = getDriveService(account)
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete a file from Google Drive.
     */
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext false

        try {
            val drive = getDriveService(account)
            drive.files().delete(fileId).execute()
            true
        } catch (e: Exception) {
            false
        }
    }
}
