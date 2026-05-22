package com.propdf.security.encryption

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Professional PDF encryption and security manager.
 * Features: password protection, AES encryption, permission restrictions.
 */
class EncryptionManager(private val context: Context) {

    companion object {
        private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"
        private const val AES_KEY_SIZE = 256
        private const val IV_SIZE = 16
    }

    // ==================== PASSWORD PROTECTION ====================

    /**
     * Add password protection to a PDF.
     */
    suspend fun passwordProtect(
        sourceUri: Uri,
        outputFile: File,
        userPassword: String,
        ownerPassword: String? = null,
        permissions: Int = EncryptionConstants.ALLOW_PRINTING
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            if (!sourceFile.exists()) return@withContext Result.failure(Exception("Source file not found"))

            val reader = PdfReader(sourceFile.absolutePath)
            val writer = PdfWriter(
                outputFile.absolutePath,
                WriterProperties().setStandardEncryption(
                    userPassword.toByteArray(),
                    (ownerPassword ?: userPassword).toByteArray(),
                    permissions,
                    EncryptionConstants.ENCRYPTION_AES_256
                )
            )

            val doc = PdfDocument(reader, writer)
            doc.close()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remove password protection (requires owner password).
     */
    suspend fun removePassword(
        sourceUri: Uri,
        outputFile: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            val reader = PdfReader(
                sourceFile.absolutePath,
                ReaderProperties().setPassword(password.toByteArray())
            )
            val writer = PdfWriter(outputFile.absolutePath)
            val doc = PdfDocument(reader, writer)
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== AES FILE ENCRYPTION ====================

    /**
     * Encrypt any file with AES-256.
     */
    suspend fun encryptFile(
        sourceFile: File,
        outputFile: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val key = deriveKey(password)
            val iv = ByteArray(IV_SIZE).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

            val input = sourceFile.readBytes()
            val encrypted = cipher.doFinal(input)

            // Write IV + encrypted data
            FileOutputStream(outputFile).use { out ->
                out.write(iv)
                out.write(encrypted)
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypt an AES-256 encrypted file.
     */
    suspend fun decryptFile(
        sourceFile: File,
        outputFile: File,
        password: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val key = deriveKey(password)
            val input = sourceFile.readBytes()

            // Extract IV and encrypted data
            val iv = input.copyOfRange(0, IV_SIZE)
            val encrypted = input.copyOfRange(IV_SIZE, input.size)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

            val decrypted = cipher.doFinal(encrypted)
            outputFile.writeBytes(decrypted)

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== PERMISSION RESTRICTIONS ====================

    /**
     * Apply permission restrictions to a PDF.
     */
    suspend fun applyRestrictions(
        sourceUri: Uri,
        outputFile: File,
        ownerPassword: String,
        allowPrinting: Boolean = true,
        allowModifications: Boolean = false,
        allowCopy: Boolean = false,
        allowAnnotations: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourceUri.path ?: return@withContext Result.failure(Exception("Invalid URI")))
            var permissions = 0
            if (allowPrinting) permissions = permissions or EncryptionConstants.ALLOW_PRINTING
            if (allowModifications) permissions = permissions or EncryptionConstants.ALLOW_MODIFY_CONTENTS
            if (allowCopy) permissions = permissions or EncryptionConstants.ALLOW_COPY
            if (allowAnnotations) permissions = permissions or EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS

            val reader = PdfReader(sourceFile.absolutePath)
            val writer = PdfWriter(
                outputFile.absolutePath,
                WriterProperties().setStandardEncryption(
                    "".toByteArray(), // No user password
                    ownerPassword.toByteArray(),
                    permissions,
                    EncryptionConstants.ENCRYPTION_AES_256
                )
            )

            val doc = PdfDocument(reader, writer)
            doc.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== UTILITY ====================

    private fun deriveKey(password: String): SecretKeySpec {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray())
        return SecretKeySpec(hash, "AES")
    }

    /**
     * Check if a PDF is encrypted.
     */
    fun isEncrypted(uri: Uri): Boolean {
        return try {
            val file = File(uri.path ?: return false)
            val reader = PdfReader(file.absolutePath)
            val encrypted = reader.isEncrypted()
            reader.close()
            encrypted
        } catch (e: Exception) {
            false
        }
    }
}

object PermissionFlags {
    const val PRINTING = EncryptionConstants.ALLOW_PRINTING
    const val MODIFY_CONTENTS = EncryptionConstants.ALLOW_MODIFY_CONTENTS
    const val COPY = EncryptionConstants.ALLOW_COPY
    const val MODIFY_ANNOTATIONS = EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS
    const val FILL_IN = EncryptionConstants.ALLOW_FILL_IN
    const val SCREEN_READERS = EncryptionConstants.ALLOW_SCREENREADERS
    const val ASSEMBLY = EncryptionConstants.ALLOW_ASSEMBLY
    const val DEGRADED_PRINTING = EncryptionConstants.ALLOW_DEGRADED_PRINTING
}
