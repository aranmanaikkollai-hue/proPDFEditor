package com.propdf.editor.domain.repository

import android.net.Uri
import com.propdf.editor.data.cloud.SyncProgress
import com.propdf.editor.domain.model.CloudAccount
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import kotlinx.coroutines.flow.Flow

interface CloudRepository {
    fun getConnectedAccounts(): Flow<List<CloudAccount>>
    fun syncAll(): Flow<SyncProgress>

    suspend fun connectGoogleDrive(): Boolean
    suspend fun connectOneDrive(): Boolean
    suspend fun connectDropbox(): Boolean

    suspend fun disconnect(provider: CloudProvider)
    suspend fun isConnected(provider: CloudProvider): Boolean

    suspend fun uploadFile(uri: Uri, fileName: String, provider: CloudProvider): String?
    suspend fun downloadFile(cloudId: String, provider: CloudProvider): ByteArray?
    suspend fun deleteFile(cloudId: String, provider: CloudProvider): Boolean

    suspend fun listCloudFiles(provider: CloudProvider): List<PdfDocument>
}
