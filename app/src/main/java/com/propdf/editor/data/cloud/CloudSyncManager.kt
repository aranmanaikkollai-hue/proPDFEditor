package com.propdf.editor.data.cloud

import android.content.Context
import android.net.Uri
import com.propdf.editor.data.cloud.drive.GoogleDriveManager
import com.propdf.editor.data.cloud.dropbox.DropboxManager
import com.propdf.editor.data.cloud.onedrive.OneDriveManager
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified cloud sync manager.
 * Coordinates sync operations across all connected cloud providers.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfDocumentDao: PdfDocumentDao,
    private val googleDrive: GoogleDriveManager,
    private val oneDrive: OneDriveManager,
    private val dropbox: DropboxManager
) {

    fun getAvailableProviders(): List<CloudProvider> {
        return listOfNotNull(
            CloudProvider.GOOGLE_DRIVE.takeIf { googleDrive.isSignedIn() },
            CloudProvider.ONEDRIVE.takeIf { oneDrive.isSignedIn() },
            CloudProvider.DROPBOX.takeIf { dropbox.isAuthenticated() }
        )
    }

    fun isAnyCloudConnected(): Boolean = getAvailableProviders().isNotEmpty()

    /**
     * Sync all cloud files to local database.
     */
    fun syncAll(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Starting)

        val providers = getAvailableProviders()
        if (providers.isEmpty()) {
            emit(SyncProgress.NoProviders)
            return@flow
        }

        var totalSynced = 0
        var totalFailed = 0

        providers.forEach { provider ->
            emit(SyncProgress.SyncingProvider(provider, 0, 0))
            try {
                val files: List<PdfDocument> = when (provider) {
                    CloudProvider.GOOGLE_DRIVE -> googleDrive.listPdfFiles()
                    CloudProvider.ONEDRIVE -> oneDrive.listPdfFiles()
                    CloudProvider.DROPBOX -> dropbox.listPdfFiles()
                    else -> emptyList()
                }

                files.forEach { doc ->
                    val existing = pdfDocumentDao.getByUri(doc.uri.toString())
                    if (existing == null) {
                        pdfDocumentDao.insert(
                            PdfDocumentEntity(
                                uri = doc.uri.toString(),
                                fileName = doc.fileName,
                                displayName = doc.displayName,
                                fileSize = doc.fileSize,
                                lastModified = doc.lastModified,
                                cloudProvider = provider.name,
                                cloudId = doc.cloudId,
                                syncStatus = SyncStatus.SYNCED.name
                            )
                        )
                        totalSynced++
                    }
                }
                emit(SyncProgress.SyncingProvider(provider, files.size, 0))
            } catch (e: Exception) {
                totalFailed++
                emit(SyncProgress.ProviderError(provider, e.message ?: "Unknown error"))
            }
        }

        emit(SyncProgress.Completed(totalSynced, totalFailed))
    }

    /**
     * Upload a local file to a specific cloud provider.
     */
    suspend fun uploadToCloud(
        uri: Uri,
        fileName: String,
        provider: CloudProvider
    ): String? {
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> googleDrive.uploadFile(uri, fileName)
            CloudProvider.ONEDRIVE -> oneDrive.uploadFile(uri, fileName)
            CloudProvider.DROPBOX -> {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    dropbox.uploadFile(stream, "/$fileName")
                }
            }
            else -> null
        }
    }

    /**
     * Download a cloud file.
     */
    suspend fun downloadFromCloud(
        cloudId: String,
        provider: CloudProvider
    ): ByteArray? {
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> googleDrive.downloadFile(cloudId)
            CloudProvider.ONEDRIVE -> oneDrive.downloadFile(cloudId)
            CloudProvider.DROPBOX -> dropbox.downloadFile(cloudId)
            else -> null
        }
    }

    /**
     * Delete a file from cloud.
     */
    suspend fun deleteFromCloud(
        cloudId: String,
        provider: CloudProvider
    ): Boolean {
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> googleDrive.deleteFile(cloudId)
            CloudProvider.ONEDRIVE -> oneDrive.deleteFile(cloudId)
            CloudProvider.DROPBOX -> dropbox.deleteFile(cloudId)
            else -> false
        }
    }
}

sealed class SyncProgress {
    object Starting : SyncProgress()
    object NoProviders : SyncProgress()
    data class SyncingProvider(val provider: CloudProvider, val synced: Int, val failed: Int) : SyncProgress()
    data class ProviderError(val provider: CloudProvider, val error: String) : SyncProgress()
    data class Completed(val totalSynced: Int, val totalFailed: Int) : SyncProgress()
}
