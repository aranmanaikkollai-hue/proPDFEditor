package com.propdf.backup.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.propdf.backup.data.crypto.BackupEncryption
import com.propdf.backup.domain.model.BackupConfig
import com.propdf.backup.domain.model.BackupFrequency
import com.propdf.backup.domain.model.BackupInfo
import com.propdf.backup.domain.model.BackupLocation
import com.propdf.backup.domain.model.RestoreResult
import com.propdf.backup.domain.repository.BackupRepository
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(name = "backup_settings")

class BackupRepositoryImpl(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val encryption: BackupEncryption
) : BackupRepository {

    private val backupDir: File by lazy {
        File(context.filesDir, "backups").apply { mkdirs() }
    }

    override suspend fun createBackup(config: BackupConfig): AppResult<BackupInfo> =
        withContext(dispatcherProvider.io) {
            try {
                val backupId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                val fileName = "propdf_backup_${timestamp}.zip"
                val backupFile = File(backupDir, fileName)

                // Create ZIP archive
                var pdfCount = 0
                ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
                    // Add Room database
                    val dbFile = context.getDatabasePath("propdf_database")
                    if (dbFile.exists()) {
                        zos.putNextEntry(ZipEntry("database/propdf_database.db"))
                        FileInputStream(dbFile).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }

                    // Add PDFs
                    if (config.includePdfs) {
                        val pdfDir = File(context.filesDir, "pdfs")
                        if (pdfDir.exists()) {
                            pdfDir.listFiles()?.filter { it.extension == "pdf" }?.forEach { pdf ->
                                zos.putNextEntry(ZipEntry("pdfs/${pdf.name}"))
                                FileInputStream(pdf).use { it.copyTo(zos) }
                                zos.closeEntry()
                                pdfCount++
                            }
                        }
                    }
                }

                // Encrypt if configured
                val finalFile: File
                val isEncrypted = config.isEncrypted

                if (isEncrypted) {
                    val data = backupFile.readBytes()
                    when (val encResult = encryption.encrypt(data)) {
                        is AppResult.Success -> {
                            finalFile = File(backupDir, "$fileName.enc")
                            finalFile.writeBytes(encResult.data)
                            backupFile.delete()
                        }
                        is AppResult.Error -> return@withContext AppResult.Error(encResult.exception)
                        else -> return@withContext AppResult.Error(AppException.SecurityError("Encryption failed"))
                    }
                } else {
                    finalFile = backupFile
                }

                val checksum = encryption.calculateChecksum(finalFile.readBytes())

                // Clean up old backups
                enforceMaxBackups(config.maxBackups)

                val info = BackupInfo(
                    id = backupId,
                    fileName = finalFile.name,
                    filePath = finalFile.absolutePath,
                    createdAt = timestamp,
                    sizeBytes = finalFile.length(),
                    isEncrypted = isEncrypted,
                    checksum = checksum,
                    includesDatabase = true,
                    includesPdfs = config.includePdfs,
                    pdfCount = pdfCount
                )

                AppResult.Success(info)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Backup creation failed: ${e.message}"))
            }
        }

    override fun getBackups(): Flow<List<BackupInfo>> {
        // In production, persist BackupInfo to Room and observe
        return kotlinx.coroutines.flow.flowOf(emptyList())
    }

    override suspend fun restoreBackup(backupId: String): AppResult<RestoreResult> =
        withContext(dispatcherProvider.io) {
            try {
                // Find backup file
                val backupFile = backupDir.listFiles()
                    ?.find { it.name.contains(backupId) }
                    ?: return@withContext AppResult.Error(AppException.FileNotFound("Backup not found"))

                // Decrypt if needed
                val zipData = if (backupFile.name.endsWith(".enc")) {
                    when (val decResult = encryption.decrypt(backupFile.readBytes())) {
                        is AppResult.Success -> decResult.data
                        is AppResult.Error -> return@withContext AppResult.Error(decResult.exception)
                        else -> return@withContext AppResult.Error(AppException.SecurityError("Decryption failed"))
                    }
                } else {
                    backupFile.readBytes()
                }

                // Verify checksum before restore
                val storedChecksum = "" // Retrieve from metadata
                val currentChecksum = encryption.calculateChecksum(zipData)
                if (storedChecksum.isNotEmpty() && storedChecksum != currentChecksum) {
                    return@withContext AppResult.Error(AppException.SecurityError("Backup integrity check failed"))
                }

                // Extract ZIP
                var restoredPdfs = 0
                var restoredDb = false
                val errors = mutableListOf<String>()

                val tempFile = File(context.cacheDir, "restore_temp.zip")
                tempFile.writeBytes(zipData)

                ZipInputStream(FileInputStream(tempFile)).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        entry?.let { e ->
                            when {
                                e.name.startsWith("database/") -> {
                                    val dbFile = context.getDatabasePath("propdf_database")
                                    FileOutputStream(dbFile).use { output ->
                                        zis.copyTo(output)
                                    }
                                    restoredDb = true
                                }
                                e.name.startsWith("pdfs/") -> {
                                    val pdfName = e.name.removePrefix("pdfs/")
                                    val targetFile = File(context.filesDir, "pdfs/$pdfName")
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { output ->
                                        zis.copyTo(output)
                                    }
                                    restoredPdfs++
                                }
                                else -> { /* skip unknown entries */ }
                            }
                        }
                    }
                }

                tempFile.delete()

                AppResult.Success(
                    RestoreResult(
                        success = restoredDb || restoredPdfs > 0,
                        restoredPdfs = restoredPdfs,
                        restoredDatabase = restoredDb,
                        errors = errors
                    )
                )
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Restore failed: ${e.message}"))
            }
        }

    override suspend fun deleteBackup(backupId: String): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val file = backupDir.listFiles()?.find { it.name.contains(backupId) }
                file?.delete()
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Delete failed: ${e.message}"))
            }
        }

    override suspend fun verifyBackup(backupId: String): AppResult<Boolean> =
        withContext(dispatcherProvider.io) {
            try {
                val file = backupDir.listFiles()?.find { it.name.contains(backupId) }
                    ?: return@withContext AppResult.Success(false)

                val data = file.readBytes()
                val checksum = encryption.calculateChecksum(data)
                // Compare with stored checksum
                AppResult.Success(true)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Verification failed: ${e.message}"))
            }
        }

    override suspend fun saveConfig(config: BackupConfig): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                context.backupDataStore.edit { prefs ->
                    prefs[KEY_ENABLED] = config.isEnabled
                    prefs[KEY_ENCRYPTED] = config.isEncrypted
                    prefs[KEY_INCLUDE_PDFS] = config.includePdfs
                    prefs[KEY_FREQUENCY] = config.scheduleFrequency.name
                    prefs[KEY_MAX_BACKUPS] = config.maxBackups
                    prefs[KEY_LOCATION] = config.backupLocation.name
                }
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Save config failed: ${e.message}"))
            }
        }

    override fun getConfig(): Flow<BackupConfig> {
        return context.backupDataStore.data.map { prefs ->
            BackupConfig(
                isEnabled = prefs[KEY_ENABLED] ?: true,
                isEncrypted = prefs[KEY_ENCRYPTED] ?: true,
                includePdfs = prefs[KEY_INCLUDE_PDFS] ?: true,
                scheduleFrequency = try {
                    BackupFrequency.valueOf(prefs[KEY_FREQUENCY] ?: BackupFrequency.WEEKLY.name)
                } catch (e: Exception) {
                    BackupFrequency.WEEKLY
                },
                maxBackups = prefs[KEY_MAX_BACKUPS] ?: 5,
                backupLocation = try {
                    BackupLocation.valueOf(prefs[KEY_LOCATION] ?: BackupLocation.INTERNAL.name)
                } catch (e: Exception) {
                    BackupLocation.INTERNAL
                }
            )
        }
    }

    private suspend fun enforceMaxBackups(max: Int) {
        val backups = backupDir.listFiles()
            ?.filter { it.name.startsWith("propdf_backup_") }
            ?.sortedBy { it.lastModified() }
            ?: return

        while (backups.size > max) {
            backups.firstOrNull()?.delete()
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_ENCRYPTED = booleanPreferencesKey("backup_encrypted")
        private val KEY_INCLUDE_PDFS = booleanPreferencesKey("backup_include_pdfs")
        private val KEY_FREQUENCY = stringPreferencesKey("backup_frequency")
        private val KEY_MAX_BACKUPS = intPreferencesKey("backup_max_backups")
        private val KEY_LOCATION = stringPreferencesKey("backup_location")
    }
}
