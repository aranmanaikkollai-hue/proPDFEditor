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
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudRepositoryImpl @Inject constructor(
    private val cloudSyncManager: CloudSyncManager,
    private val cloudAccountDao: CloudAccountDao
) : CloudRepository {

    override fun getConnectedAccounts(): Flow<List<CloudAccount>> = flow {
        val entities = cloudAccountDao.getActiveAccounts()
        // Collect the flow and transform
        entities.collect { list ->
            emit(list.map { entity ->
                CloudAccount(
                    id = entity.id,
                    provider = CloudProvider.valueOf(entity.provider),
                    accountId = entity.accountId,
                    accountEmail = entity.accountEmail,
                    accessToken = entity.accessToken,
                    refreshToken = entity.refreshToken,
                    tokenExpiry = entity.tokenExpiry,
                    isActive = entity.isActive
                )
            })
        }
    }

    override fun syncAll(): Flow<SyncProgress> = cloudSyncManager.syncAll()

    override suspend fun connectGoogleDrive(): Boolean = true
    override suspend fun connectOneDrive(): Boolean = true
    override suspend fun connectDropbox(): Boolean = true

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
        return emptyList()
    }
}
