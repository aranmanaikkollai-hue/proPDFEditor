package com.propdf.nas.data.repository

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.nas.data.smb.SmbClient
import com.propdf.nas.data.webdav.WebDavClient
import com.propdf.nas.domain.model.NasConfig
import com.propdf.nas.domain.model.NasOperationType
import com.propdf.nas.domain.model.PendingNasOperation
import com.propdf.nas.domain.model.RemoteFile
import com.propdf.nas.domain.repository.NasRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NasRepositoryImpl @Inject constructor(
    private val context: Context,
    private val webDavClient: WebDavClient,
    private val smbClient: SmbClient,
    private val dispatcherProvider: DispatcherProvider
) : NasRepository {

    override suspend fun testConnection(config: NasConfig): AppResult<Boolean> =
        when (config) {
            is NasConfig.WebDavConfig -> webDavClient.testConnection(config)
            is NasConfig.SmbConfig -> smbClient.testConnection(config)
        }

    override suspend fun listFiles(config: NasConfig, remotePath: String): AppResult<List<RemoteFile>> =
        when (config) {
            is NasConfig.WebDavConfig -> webDavClient.listFiles(config, remotePath)
            is NasConfig.SmbConfig -> smbClient.listFiles(config, remotePath)
        }

    override suspend fun uploadFile(
        config: NasConfig,
        localUri: Uri,
        remotePath: String
    ): AppResult<Unit> = withContext(dispatcherProvider.io) {
        try {
            val inputStream = context.contentResolver.openInputStream(localUri)
                ?: return@withContext AppResult.Error(AppException.IOError("Cannot open input stream for $localUri"))
            val size = context.contentResolver.openFileDescriptor(localUri, "r")?.statSize ?: -1L
            inputStream.use {
                when (config) {
                    is NasConfig.WebDavConfig -> webDavClient.upload(config, remotePath, it, size)
                    is NasConfig.SmbConfig -> smbClient.upload(config, remotePath, it)
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Upload failed: ${e.message}"))
        }
    }

    override suspend fun downloadFile(
        config: NasConfig,
        remotePath: String,
        localUri: Uri
    ): AppResult<Unit> = withContext(dispatcherProvider.io) {
        try {
            val streamResult = when (config) {
                is NasConfig.WebDavConfig -> webDavClient.download(config, remotePath)
                is NasConfig.SmbConfig -> smbClient.download(config, remotePath)
            }
            when (streamResult) {
                is AppResult.Success -> {
                    val outputStream = context.contentResolver.openOutputStream(localUri)
                        ?: return@withContext AppResult.Error(AppException.IOError("Cannot open output stream for $localUri"))
                    outputStream.use { out -> streamResult.data.use { it.copyTo(out) } }
                    AppResult.Success(Unit)
                }
                is AppResult.Error -> AppResult.Error(streamResult.exception)
                else -> AppResult.Error(AppException.IOError("Download failed"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Download failed: ${e.message}"))
        }
    }

    override suspend fun deleteFile(config: NasConfig, remotePath: String): AppResult<Unit> =
        when (config) {
            is NasConfig.WebDavConfig -> webDavClient.delete(config, remotePath)
            is NasConfig.SmbConfig -> smbClient.delete(config, remotePath)
        }

    // Offline queue - in-memory stub; replace with Room persistence if needed
    private val pendingOps = mutableListOf<PendingNasOperation>()

    override suspend fun queueOperation(operation: PendingNasOperation): AppResult<Unit> {
        pendingOps.add(operation)
        return AppResult.Success(Unit)
    }

    override fun getPendingOperations(): Flow<List<PendingNasOperation>> =
        flowOf(pendingOps.toList())

    override suspend fun processPendingOperations(): AppResult<Int> {
        var processed = 0
        val iterator = pendingOps.iterator()
        while (iterator.hasNext()) {
            val op = iterator.next()
            val result: AppResult<*> = when (op.operationType) {
                NasOperationType.DELETE -> {
                    val config = getConfigById(op.configId) ?: continue
                    deleteFile(config, op.remotePath)
                }
                NasOperationType.UPLOAD -> {
                    val config = getConfigById(op.configId) ?: continue
                    val uri = op.localUri?.let { Uri.parse(it) } ?: continue
                    uploadFile(config, uri, op.remotePath)
                }
                else -> continue
            }
            if (result is AppResult.Success) {
                iterator.remove()
                processed++
            }
        }
        return AppResult.Success(processed)
    }

    override suspend fun removePendingOperation(id: Long): AppResult<Unit> {
        pendingOps.removeAll { it.id == id }
        return AppResult.Success(Unit)
    }

    // Saved configs - in-memory stub; replace with Room persistence if needed
    private val savedConfigs = mutableListOf<NasConfig>()

    override suspend fun saveConfig(config: NasConfig): AppResult<Unit> {
        savedConfigs.removeAll { it.id == config.id }
        savedConfigs.add(config)
        return AppResult.Success(Unit)
    }

    override suspend fun deleteConfig(id: Long): AppResult<Unit> {
        savedConfigs.removeAll { it.id == id }
        return AppResult.Success(Unit)
    }

    override fun getConfigs(): Flow<List<NasConfig>> =
        flowOf(savedConfigs.toList())

    private fun getConfigById(id: Long): NasConfig? =
        savedConfigs.find { it.id == id }
}
