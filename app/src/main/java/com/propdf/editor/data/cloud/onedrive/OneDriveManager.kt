package com.propdf.editor.data.cloud.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft OneDrive integration manager.
 * Uses MSAL for authentication and Microsoft Graph API for file operations.
 */
@Singleton
class OneDriveManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val AUTHORITY = "https://login.microsoftonline.com/common"
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private const val PDF_FILTER = "file/mimeType eq 'application/pdf'"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var accessToken: String? = null

    init {
        initializeMsal()
    }

    private fun initializeMsal() {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalApp = application
                }
                override fun onError(exception: MsalException) {}
            }
        )
    }

    fun getSignInIntent(): Intent? {
        return msalApp?.signInIntent
    }

    suspend fun signIn(activity: android.app.Activity): Boolean = suspendCancellableCoroutine { cont ->
        msalApp?.signIn(activity, "", arrayOf("Files.Read", "Files.ReadWrite"),
            object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    accessToken = result.accessToken
                    cont.resume(true)
                }
                override fun onError(exception: MsalException) {
                    cont.resume(false)
                }
                override fun onCancel() {
                    cont.resume(false)
                }
            }
        )
    }

    fun signOut() {
        msalApp?.signOut()
        accessToken = null
    }

    fun isSignedIn(): Boolean = accessToken != null

    /**
     * List PDF files from OneDrive.
     */
    suspend fun listPdfFiles(): List<PdfDocument> = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext emptyList()

        val request = Request.Builder()
            .url("$GRAPH_BASE/me/drive/root/search(q='.pdf')")
            .header("Authorization", "Bearer $token")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(response.body?.string() ?: "")
                val values = json.getJSONArray("value")

                List(values.length()) { i ->
                    val item = values.getJSONObject(i)
                    PdfDocument(
                        uri = Uri.parse(item.optString("webUrl", "")),
                        fileName = item.optString("name", "Unknown"),
                        displayName = item.optString("name", "Unknown"),
                        fileSize = item.optJSONObject("size")?.optLong("value", 0) ?: 0,
                        lastModified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            .parse(item.optString("lastModifiedDateTime", ""))?.time ?: 0,
                        cloudProvider = CloudProvider.ONEDRIVE,
                        cloudId = item.optString("id"),
                        syncStatus = SyncStatus.SYNCED
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Upload a file to OneDrive.
     */
    suspend fun uploadFile(uri: Uri, fileName: String): String? = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext null

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val request = Request.Builder()
                .url("$GRAPH_BASE/me/drive/root:/$fileName:/content")
                .header("Authorization", "Bearer $token")
                .put(RequestBody.create(MediaType.parse("application/pdf"), bytes))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    JSONObject(response.body?.string() ?: "").optString("id")
                } else null
            }
        }
    }

    /**
     * Download a file from OneDrive.
     */
    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext null

        val request = Request.Builder()
            .url("$GRAPH_BASE/me/drive/items/$fileId/content")
            .header("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }

    /**
     * Delete a file from OneDrive.
     */
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext false

        val request = Request.Builder()
            .url("$GRAPH_BASE/me/drive/items/$fileId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()

        httpClient.newCall(request).execute().use { it.isSuccessful }
    }
}
