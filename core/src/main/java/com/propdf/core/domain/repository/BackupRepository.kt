package com.propdf.core.domain.repository

import android.net.Uri

interface BackupRepository {
    suspend fun createBackup(destination: Uri): Result<Unit>
    suspend fun restoreBackup(source: Uri): Result<Unit>
    suspend fun exportSettings(destination: Uri): Result<Unit>
}
