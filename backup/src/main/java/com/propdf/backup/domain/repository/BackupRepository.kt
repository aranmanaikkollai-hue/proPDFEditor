package com.propdf.backup.domain.repository

import com.propdf.backup.domain.model.BackupConfig
import com.propdf.backup.domain.model.BackupInfo
import com.propdf.backup.domain.model.RestoreResult
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository for encrypted local backup operations.
 */
interface BackupRepository {
    /**
     * Create a backup immediately.
     */
    suspend fun createBackup(config: BackupConfig): AppResult<BackupInfo>

    /**
     * Get list of existing backups.
     */
    fun getBackups(): Flow<List<BackupInfo>>

    /**
     * Restore from a backup.
     */
    suspend fun restoreBackup(backupId: String): AppResult<RestoreResult>

    /**
     * Delete a backup.
     */
    suspend fun deleteBackup(backupId: String): AppResult<Unit>

    /**
     * Verify backup integrity.
     */
    suspend fun verifyBackup(backupId: String): AppResult<Boolean>

    /**
     * Save backup configuration.
     */
    suspend fun saveConfig(config: BackupConfig): AppResult<Unit>

    /**
     * Get current configuration.
     */
    fun getConfig(): Flow<BackupConfig>
}
