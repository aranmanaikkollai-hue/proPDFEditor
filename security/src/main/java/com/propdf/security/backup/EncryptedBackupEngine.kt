package com.propdf.security.backup

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.model.EncryptedBackupConfig
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.security.keystore.KeystoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted backup engine for secure offline backups.
 */
@Singleton
class EncryptedBackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager
) {

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
    }

    suspend fun createEncryptedBackup(
        sources: List<File>,
        outputUri: Uri,
        config: EncryptedBackupConfig
    ): AppResult<Long> = withContext(Dispatchers.IO) {
        try {
            val tempZip = File(context.cacheDir, "backup_${System.currentTimeMillis()}.zip")
            createZipArchive(sources, tempZip)

            val zipBytes = tempZip.readBytes()

            val encrypted = try {
                keystoreManager.encryptWithPassword(zipBytes, config.password)
            } finally {
                zipBytes.fill(0)
            }

            context.contentResolver.openOutputStream(outputUri)?.use { out ->
                out.write(encrypted.toByteArray(Charsets.UTF_8))
            } ?: return@withContext AppResult.Error(
                AppException.IOError("Failed to open output stream for backup")
            )

            secureDelete(tempZip)

            val bytesWritten = encrypted.length.toLong()
            AppResult.Success(bytesWritten)
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Backup creation failed: ${e.message}")
            )
        }
    }

    suspend fun restoreEncryptedBackup(
        backupUri: Uri,
        destinationDir: File,
        password: CharArray
    ): AppResult<List<String>> = withContext(Dispatchers.IO) {
        try {
            val encryptedData = context.contentResolver.openInputStream(backupUri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return@withContext AppResult.Error(
                AppException.IOError("Failed to read backup file")
            )

            val zipBytes = keystoreManager.decryptWithPassword(encryptedData, password)

            val tempZip = File(context.cacheDir, "restore_${System.currentTimeMillis()}.zip")
            tempZip.writeBytes(zipBytes)
            zipBytes.fill(0)

            val restoredFiles = extractZipArchive(tempZip, destinationDir)
            secureDelete(tempZip)

            AppResult.Success(restoredFiles)
        } catch (e: javax.crypto.AEADBadTagException) {
            AppResult.Error(AppException.SecurityError("Invalid password or corrupted backup"))
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Backup restoration failed: ${e.message}")
            )
        }
    }

    private fun createZipArchive(sources: List<File>, outputFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            sources.forEach { source ->
                if (source.isDirectory) {
                    zipDirectory(source, source.name, zos)
                } else {
                    zipFile(source, source.name, zos)
                }
            }
        }
    }

    private fun zipDirectory(dir: File, basePath: String, zos: ZipOutputStream) {
        dir.listFiles()?.forEach { file ->
            val entryName = "$basePath/${file.name}"
            if (file.isDirectory) {
                zipDirectory(file, entryName, zos)
            } else {
                zipFile(file, entryName, zos)
            }
        }
    }

    private fun zipFile(file: File, entryName: String, zos: ZipOutputStream) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        file.inputStream().use { input ->
            input.copyTo(zos, BUFFER_SIZE)
        }
        zos.closeEntry()
    }

    private fun extractZipArchive(zipFile: File, destinationDir: File): List<String> {
        destinationDir.mkdirs()
        val restored = mutableListOf<String>()

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                entry?.let { ze ->
                    val outFile = File(destinationDir, ze.name)
                    outFile.parentFile?.mkdirs()

                    outFile.outputStream().use { output ->
                        zis.copyTo(output, BUFFER_SIZE)
                    }
                    restored.add(outFile.absolutePath)
                }
            }
        }
        return restored
    }

    private fun secureDelete(file: File) {
        if (!file.exists()) return
        try {
            val random = java.security.SecureRandom()
            val buffer = ByteArray(8192)
            file.outputStream().use { out ->
                var remaining = file.length()
                while (remaining > 0) {
                    random.nextBytes(buffer)
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    out.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
            }
        } catch (_: Exception) { }
        file.delete()
    }
}
