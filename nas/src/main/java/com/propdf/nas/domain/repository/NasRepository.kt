package com.propdf.nas.domain.repository

import android.net.Uri
import com.propdf.core.domain.result.AppResult
import com.propdf.nas.domain.model.NasConfig
import com.propdf.nas.domain.model.PendingNasOperation
import kotlinx.coroutines.flow.Flow

/**
 * Repository for WebDAV/SMB NAS operations.
 */
interface NasRepository {
    suspend fun testConnection(config: NasConfig): AppResult<Boolean>
    suspend fun listFiles(config: NasConfig, remotePath: String): AppResult<List<RemoteFile>>
    suspend fun uploadFile(config: NasConfig, localUri: Uri, remotePath: String): AppResult<Unit>
    suspend fun downloadFile(config: NasConfig, remotePath: String, localUri: Uri): AppResult<Unit>
    suspend fun deleteFile(config: NasConfig, remotePath: String): AppResult<Unit>

    // Offline queue
    suspend fun queueOperation(operation: PendingNasOperation): AppResult<Unit>
    fun getPendingOperations(): Flow<List<PendingNasOperation>>
    suspend fun processPendingOperations(): AppResult<Int> // Returns processed count
    suspend fun removePendingOperation(id: Long): AppResult<Unit>

    // Saved configs
    suspend fun saveConfig(config: NasConfig): AppResult<Unit>
    suspend fun deleteConfig(id: Long): AppResult<Unit>
    fun getConfigs(): Flow<List<NasConfig>>
}

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
