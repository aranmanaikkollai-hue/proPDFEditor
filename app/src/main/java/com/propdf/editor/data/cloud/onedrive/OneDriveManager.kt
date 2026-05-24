package com.propdf.editor.data.cloud.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Microsoft OneDrive integration manager.
 * 
 * NOTE: For full OneDrive integration, add this dependency:
 *   implementation 'com.microsoft.identity.client:msal:5.2.1'
 * 
 * And replace the stub methods with actual MSAL + Graph API calls.
 */
@Singleton
class OneDriveManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
    }

    private val prefs = context.getSharedPreferences("onedrive_prefs", Context.MODE_PRIVATE)
    private var accessToken: String? = null

    fun getSignInIntent(): Intent? {
        // Stub: Return null until MSAL is configured
        return null
    }

    suspend fun signIn(activity: android.app.Activity): Boolean {
        // Stub
        return false
    }

    fun signOut() {
        accessToken = null
        prefs.edit().remove("access_token").apply()
    }

    fun isSignedIn(): Boolean = accessToken != null || prefs.getString("access_token", null) != null

    suspend fun listPdfFiles(): List<PdfDocument> = withContext(Dispatchers.IO) {
        // Stub
        emptyList()
    }

    suspend fun uploadFile(uri: Uri, fileName: String): String? = withContext(Dispatchers.IO) {
        // Stub
        null
    }

    suspend fun downloadFile(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        // Stub
        null
    }

    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        // Stub
        false
    }
}
