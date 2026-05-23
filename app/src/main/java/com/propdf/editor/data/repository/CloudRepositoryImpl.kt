package com.propdf.editor.data.repository

import android.net.Uri
import com.propdf.editor.data.cloud.CloudSyncManager
import com.propdf.editor.data.cloud.SyncProgress
import com.propdf.editor.data.local.dao.CloudAccountDao
import com.propdf.editor.domain.model.CloudAccount
import com.propdf.editor.domain.model.CloudProvider
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.repository.CloudRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudRepositoryImpl @Inject constructor(
    private val cloudSyncManager: CloudSyncManager,
    private val cloudAccountDao: CloudAccountDao
) : CloudRepository {

    override fun getConnectedAccounts(): Flow<List<CloudAccount>> {
        return cloudAccountDao.getActiveAccounts().map { list ->
            list.map {
                CloudAccount(
                    id = it.id,
                    provider = CloudProvider.valueOf(it.provider),
                    accountId = it.accountId,
                    accountEmail = it.accountEmail,
                    accessToken = it.accessToken,
                    refreshToken = it.refreshToken,
                    tokenExpiry = it.tokenExpiry,
                    isActive = it.isActive
                )
            }
        }
    }

    override fun syncAll(): Flow<SyncProgress> = cloudSyncManager.syncAll()

    override suspend fun connectGoogleDrive(): Boolean {
        // Would trigger auth flow and save to database
        return true
    }

    override suspend fun connectOneDrive(): Boolean {
        return true
    }

    override suspend fun connectDropbox(): Boolean {
        return true
    }

    override suspend fun disconnect(provider: CloudProvider) {
        cloudAccountDao.getByProvider(provider.name)?.let {
            cloudAccountDao.deactivate(it.id)
        }
    }

    override suspend fun isConnected(provider: CloudProvider): Boolean {
        return cloudAccountDao.getByProvider(provider.name) != null
    }

    override suspend fun uploadFile(uri: Uri, fileName: String, provider: CloudProvider): String? {
        return cloudSyncManager.uploadToCloud(uri, fileName, provider)
    }

    override suspend fun downloadFile(cloudId: String, provider: CloudProvider): ByteArray? {
        return cloudSyncManager.downloadFromCloud(cloudId, provider)
    }

    override suspend fun deleteFile(cloudId: String, provider: CloudProvider): Boolean {
        return cloudSyncManager.deleteFromCloud(cloudId, provider)
    }

    override suspend fun listCloudFiles(provider: CloudProvider): List<PdfDocument> {
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> cloudSyncManager.googleDrive.listPdfFiles()
            CloudProvider.ONEDRIVE -> cloudSyncManager.oneDrive.listPdfFiles()
            CloudProvider.DROPBOX -> cloudSyncManager.dropbox.listPdfFiles()
            else -> emptyList()
        }
    }
}
