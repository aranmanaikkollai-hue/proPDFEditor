package com.propdf.security.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.propdf.core.domain.model.VaultEntry
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.security.keystore.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Vault encryption engine for secure document storage.
 */
class VaultEncryptionEngine(
    private val context: Context,
    private val keystoreManager: KeystoreManager
) {

    companion object {
        private const val VAULT_DIR = "vault"
        private const val THUMBNAIL_DIR = "vault_thumbs"
        private const val THUMBNAIL_SIZE = 256
    }

    private val vaultDir: File by lazy {
        File(context.filesDir, VAULT_DIR).apply { mkdirs() }
    }

    private val thumbnailDir: File by lazy {
        File(context.filesDir, THUMBNAIL_DIR).apply { mkdirs() }
    }

    suspend fun encryptToVault(
        sourceFile: File,
        useBiometricKey: Boolean = false
    ): AppResult<VaultEntry> = withContext(Dispatchers.IO) {
        try {
            val key = if (useBiometricKey) {
                keystoreManager.getOrCreateBiometricKey()
            } else {
                keystoreManager.getOrCreateMasterKey()
            }

            val entryId = UUID.randomUUID().toString()
            val encryptedFile = File(vaultDir, "$entryId.enc")
            val thumbnailFile = File(thumbnailDir, "$entryId.jpg")

            val pageCount = getPageCount(sourceFile)
            val fileSize = sourceFile.length()

            keystoreManager.encryptFile(sourceFile, encryptedFile, key)
            generateThumbnail(sourceFile, thumbnailFile, key)
            secureDelete(sourceFile)

            val entry = VaultEntry(
                id = entryId,
                originalFileName = sourceFile.name,
                encryptedFilePath = encryptedFile.absolutePath,
                thumbnailPath = thumbnailFile.absolutePath,
                encryptedAt = System.currentTimeMillis(),
                pageCount = pageCount,
                fileSizeBytes = fileSize
            )

            AppResult.Success(entry)
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Failed to encrypt to vault: ${e.message}")
            )
        }
    }

    suspend fun decryptFromVault(
        entry: VaultEntry,
        useBiometricKey: Boolean = false
    ): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val key = if (useBiometricKey) {
                keystoreManager.getOrCreateBiometricKey()
            } else {
                keystoreManager.getOrCreateMasterKey()
            }

            val tempFile = File(context.cacheDir, "vault_temp_${entry.id}_${System.currentTimeMillis()}.pdf")
            val encryptedFile = File(entry.encryptedFilePath)

            if (!encryptedFile.exists()) {
                return@withContext AppResult.Error(
                    AppException.SecurityError("Encrypted file not found: ${entry.encryptedFilePath}")
                )
            }

            keystoreManager.decryptFile(encryptedFile, tempFile, key)
            AppResult.Success(tempFile)
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Failed to decrypt from vault: ${e.message}")
            )
        }
    }

    suspend fun deleteVaultEntry(entry: VaultEntry): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            File(entry.encryptedFilePath).let { if (it.exists()) secureDelete(it) }
            entry.thumbnailPath?.let { path ->
                File(path).let { if (it.exists()) secureDelete(it) }
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Failed to delete vault entry: ${e.message}")
            )
        }
    }

    suspend fun exportFromVault(
        entry: VaultEntry,
        destinationFile: File,
        useBiometricKey: Boolean = false
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val key = if (useBiometricKey) {
                keystoreManager.getOrCreateBiometricKey()
            } else {
                keystoreManager.getOrCreateMasterKey()
            }

            val encryptedFile = File(entry.encryptedFilePath)
            if (!encryptedFile.exists()) {
                return@withContext AppResult.Error(
                    AppException.SecurityError("Encrypted file not found")
                )
            }

            keystoreManager.decryptFile(encryptedFile, destinationFile, key)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error(
                AppException.SecurityError("Failed to export from vault: ${e.message}")
            )
        }
    }

    private suspend fun generateThumbnail(
        pdfFile: File,
        outputFile: File,
        key: SecretKey
    ) = withContext(Dispatchers.IO) {
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            if (renderer.pageCount > 0) {
                renderer.openPage(0).use { page ->
                    val ratio = page.width.toFloat() / page.height.toFloat()
                    val width = THUMBNAIL_SIZE
                    val height = (width / ratio).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val jpegBytes = java.io.ByteArrayOutputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        stream.toByteArray()
                    }
                    bitmap.recycle()

                    val encrypted = keystoreManager.encrypt(jpegBytes, key)
                    outputFile.writeText(encrypted)
                }
            }
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    suspend fun decryptThumbnail(
        entry: VaultEntry,
        useBiometricKey: Boolean = false
    ): AppResult<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val thumbPath = entry.thumbnailPath
                ?: return@withContext AppResult.Error(AppException.SecurityError("No thumbnail available"))

            val key = if (useBiometricKey) {
                keystoreManager.getOrCreateBiometricKey()
            } else {
                keystoreManager.getOrCreateMasterKey()
            }

            val encryptedData = File(thumbPath).readText()
            val jpegBytes = keystoreManager.decrypt(encryptedData, key)
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return@withContext AppResult.Error(AppException.SecurityError("Failed to decode thumbnail"))

            AppResult.Success(bitmap)
        } catch (e: Exception) {
            AppResult.Error(AppException.SecurityError("Failed to decrypt thumbnail: ${e.message}"))
        }
    }

    private fun getPageCount(file: File): Int {
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (_: Exception) {
            0
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    private fun secureDelete(file: File) {
        if (!file.exists()) return
        try {
            val length = file.length()
            val random = SecureRandom()
            val buffer = ByteArray(8192)

            file.outputStream().use { out ->
                var remaining = length
                while (remaining > 0) {
                    random.nextBytes(buffer)
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    out.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                out.flush()
            }
        } catch (_: Exception) {
            // Best effort
        } finally {
            file.delete()
        }
    }

    fun getVaultSizeBytes(): Long {
        return vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getVaultEntryCount(): Int {
        return vaultDir.listFiles()?.count { it.extension == "enc" } ?: 0
    }
}
