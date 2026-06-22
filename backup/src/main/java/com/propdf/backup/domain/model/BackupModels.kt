package com.propdf.backup.domain.model

import java.util.Date

/**
 * Represents a backup archive with metadata.
 */
data class BackupInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val isEncrypted: Boolean,
    val checksum: String,
    val includesDatabase: Boolean,
    val includesPdfs: Boolean,
    val pdfCount: Int
)

/**
 * Backup configuration settings.
 */
data class BackupConfig(
    val isEnabled: Boolean = true,
    val isEncrypted: Boolean = true,
    val includePdfs: Boolean = true,
    val scheduleFrequency: BackupFrequency = BackupFrequency.WEEKLY,
    val maxBackups: Int = 5,
    val backupLocation: BackupLocation = BackupLocation.INTERNAL
)

enum class BackupFrequency {
    DAILY, WEEKLY, MONTHLY, MANUAL
}

enum class BackupLocation {
    INTERNAL, SAF_TREE, EXTERNAL_SD
}

/**
 * Result of a restore operation.
 */
data class RestoreResult(
    val success: Boolean,
    val restoredPdfs: Int,
    val restoredDatabase: Boolean,
    val errors: List<String>
)
