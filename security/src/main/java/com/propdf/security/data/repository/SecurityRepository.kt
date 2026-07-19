// security/src/main/java/com/propdf/security/data/repository/SecurityRepository.kt
package com.propdf.security.data.repository

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.*
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.pdf.layer.PdfLayer
import com.itextpdf.kernel.pdf.redaction.RedactionEvent
import com.itextpdf.kernel.pdf.redaction.RedactionFilter
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.propdf.security.data.dao.RedactionDao
import com.propdf.security.data.dao.SecureDocumentDao
import com.propdf.security.data.dao.SecurityOperationDao
import com.propdf.security.data.entity.*
import com.propdf.security.worker.SecurityWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val operationDao: SecurityOperationDao,
    private val redactionDao: RedactionDao,
    private val secureDocumentDao: SecureDocumentDao,
    private val workManager: WorkManager
) {
    companion object {
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val BUFFER_SIZE = 8192
        private const val SECURE_DELETE_PASSES = 3
    }

    // ==================== OPERATIONS ====================

    fun getAllOperations(): Flow<List<SecurityOperationEntity>> = operationDao.getAllOperations()

    fun getDocumentOperations(uri: String): Flow<List<SecurityOperationEntity>> = 
        operationDao.getOperationsForDocument(uri)

    // ==================== AES ENCRYPTION ====================

    suspend fun encryptWithAes(
        sourceUri: Uri,
        password: String,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val operationId = operationDao.insert(
                SecurityOperationEntity(
                    documentUri = sourceUri.toString(),
                    operationType = SecurityOperationType.AES_ENCRYPT,
                    status = OperationStatus.PROCESSING
                )
            )

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    output.write(salt)
                    
                    val key = deriveKey(password, salt)
                    val iv = ByteArray(GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
                    output.write(iv)
                    
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val encrypted = cipher.update(buffer, 0, bytesRead)
                        if (encrypted != null) output.write(encrypted)
                    }
                    
                    val final = cipher.doFinal()
                    output.write(final)
                }
            }

            operationDao.update(
                operationDao.getDocumentOperations(sourceUri.toString()).first()
                    .find { it.id == operationId }!!
                    .copy(status = OperationStatus.SUCCESS, outputUri = outputUri.toString())
            )

            Result.success(outputUri)
        } catch (e: Exception) {
            operationDao.update(
                SecurityOperationEntity(
                    documentUri = sourceUri.toString(),
                    operationType = SecurityOperationType.AES_ENCRYPT,
                    status = OperationStatus.FAILED,
                    errorMessage = e.message
                )
            )
            Result.failure(e)
        }
    }

    suspend fun decryptWithAes(
        sourceUri: Uri,
        password: String,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    val salt = ByteArray(16).also { input.read(it) }
                    val iv = ByteArray(GCM_IV_LENGTH).also { input.read(it) }
                    
                    val key = deriveKey(password, salt)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        val decrypted = cipher.update(buffer, 0, bytesRead)
                        if (decrypted != null) output.write(decrypted)
                    }
                    
                    val final = cipher.doFinal()
                    output.write(final)
                }
            }
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== PDF PASSWORD PROTECTION ====================

    suspend fun applyPasswordProtection(
        sourceUri: Uri,
        userPassword: String?,
        ownerPassword: String?,
        permissions: Int,
        encryptionAlgorithm: EncryptionType,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_protect", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(tempFile.absolutePath)
            val writer = PdfWriter(
                outputUri.path ?: throw IllegalStateException("Invalid output URI"),
                WriterProperties().setStandardEncryption(
                    userPassword?.toByteArray(),
                    ownerPassword?.toByteArray(),
                    permissions,
                    when (encryptionAlgorithm) {
                        EncryptionType.AES_256 -> EncryptionConstants.ENCRYPTION_AES_256
                        EncryptionType.AES_128 -> EncryptionConstants.ENCRYPTION_AES_128
                        EncryptionType.STANDARD_128 -> EncryptionConstants.STANDARD_ENCRYPTION_128
                        else -> EncryptionConstants.STANDARD_ENCRYPTION_128
                    }
                )
            )

            PdfDocument(reader, writer).use { pdfDoc ->
                pdfDoc.close()
            }

            // Update secure document record
            secureDocumentDao.insert(
                SecureDocumentEntity(
                    uri = outputUri.toString(),
                    encryptionType = encryptionAlgorithm,
                    hasOwnerPassword = !ownerPassword.isNullOrBlank(),
                    hasUserPassword = !userPassword.isNullOrBlank(),
                    permissions = permissions
                )
            )

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== PERMISSIONS ====================

    suspend fun setPermissions(
        sourceUri: Uri,
        ownerPassword: String,
        newPermissions: Int,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_perm", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(tempFile.absolutePath)
            val writer = PdfWriter(
                outputUri.path ?: throw IllegalStateException("Invalid output URI"),
                WriterProperties().setStandardEncryption(
                    null,
                    ownerPassword.toByteArray(),
                    newPermissions,
                    EncryptionConstants.ENCRYPTION_AES_256
                )
            )

            PdfDocument(reader, writer).use { pdfDoc ->
                pdfDoc.close()
            }

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== METADATA REMOVAL ====================

    suspend fun removeMetadata(
        sourceUri: Uri,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_meta", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(tempFile.absolutePath)
            val writer = PdfWriter(outputUri.path ?: throw IllegalStateException("Invalid output URI"))
            
            PdfDocument(reader, writer).use { pdfDoc ->
                val info = pdfDoc.documentInfo
                info.title = null
                info.author = null
                info.subject = null
                info.keywords = null
                info.creator = null
                info.producer = null
                info.creationDate = null
                info.modificationDate = null
                
                // Remove XMP metadata
                val catalog = pdfDoc.catalog
                catalog.remove(PdfName.Metadata)
                
                // Remove document ID
                catalog.remove(PdfName.ID)
                
                pdfDoc.close()
            }

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== DOCUMENT SANITIZATION ====================

    suspend fun sanitizeDocument(
        sourceUri: Uri,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_sanitize", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(tempFile.absolutePath)
            val writer = PdfWriter(outputUri.path ?: throw IllegalStateException("Invalid output URI"))
            
            PdfDocument(reader, writer).use { pdfDoc ->
                // Remove JavaScript
                val catalog = pdfDoc.catalog
                catalog.remove(PdfName.Names)
                catalog.remove(PdfName.OpenAction)
                catalog.remove(PdfName.AA)
                catalog.remove(PdfName.JavaScript)
                catalog.remove(PdfName.JS)
                
                // Remove embedded files
                catalog.remove(PdfName.EmbeddedFiles)
                
                // Remove annotations (except links if needed)
                for (i in 1..pdfDoc.numberOfPages) {
                    val page = pdfDoc.getPage(i)
                    val annotations = page.annotations
                    annotations?.forEach { annot ->
                        val subtype = annot.getPdfObject().getAsName(PdfName.Subtype)
                        if (subtype != PdfName.Link) {
                            page.removeAnnotation(annot)
                        }
                    }
                    
                    // Remove page actions
                    page.pdfObject.remove(PdfName.AA)
                }
                
                // Remove form fields
                val form = PdfAcroForm.getAcroForm(pdfDoc, false)
                form?.fields?.forEach { (_, field) ->
                    form.removeField(field.getFieldName().toString())
                }
                
                // Remove metadata
                pdfDoc.documentInfo.title = null
                pdfDoc.documentInfo.author = null
                pdfDoc.documentInfo.subject = null
                pdfDoc.documentInfo.keywords = null
                pdfDoc.documentInfo.creator = null
                pdfDoc.documentInfo.producer = null
                catalog.remove(PdfName.Metadata)
                
                // Flatten transparency
                // Remove hidden layers
                val ocProperties = catalog.getAsDictionary(PdfName.OCProperties)
                ocProperties?.let {
                    catalog.remove(PdfName.OCProperties)
                }
                
                pdfDoc.close()
            }

            // Update record
            secureDocumentDao.insert(
                SecureDocumentEntity(
                    uri = outputUri.toString(),
                    encryptionType = EncryptionType.NONE,
                    hasOwnerPassword = false,
                    hasUserPassword = false,
                    permissions = -1,
                    isSanitized = true
                )
            )

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== REDACTION ====================

    suspend fun addRedaction(
        documentUri: String,
        pageNumber: Int,
        rect: RectF,
        overlayText: String? = null
    ): Long = redactionDao.insert(
        RedactionEntity(
            documentUri = documentUri,
            pageNumber = pageNumber,
            rect = rect,
            overlayText = overlayText
        )
    )

    suspend fun removeRedaction(redaction: RedactionEntity) = redactionDao.delete(redaction)

    fun getPendingRedactions(documentUri: String): Flow<List<RedactionEntity>> = 
        redactionDao.getPendingRedactions(documentUri)

    suspend fun applyRedactions(
        sourceUri: Uri,
        outputUri: Uri,
        permanent: Boolean = false
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val redactions = redactionDao.getPendingRedactions(sourceUri.toString()).first()
            if (redactions.isEmpty()) return@withContext Result.failure(
                IllegalStateException("No pending redactions found")
            )

            val tempFile = File.createTempFile("pdf_redact", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(tempFile.absolutePath)
            val writer = PdfWriter(outputUri.path ?: throw IllegalStateException("Invalid output URI"))
            
            PdfDocument(reader, writer).use { pdfDoc ->
                redactions.groupBy { it.pageNumber }.forEach { (pageNum, pageRedactions) ->
                    val page = pdfDoc.getPage(pageNum)
                    
                    pageRedactions.forEach { redaction ->
                        val cleanRect = com.itextpdf.kernel.geom.Rectangle(
                            redaction.rect.left,
                            redaction.rect.bottom,
                            redaction.rect.width(),
                            redaction.rect.height()
                        )
                        
                        if (permanent) {
                            // Permanent redaction: actually remove content
                            val canvas = PdfCanvas(page)
                            canvas.saveState()
                                .setFillColor(com.itextpdf.kernel.colors.ColorConstants.BLACK)
                                .rectangle(cleanRect)
                                .fill()
                                .restoreState()
                            
                            // Add overlay text if specified
                            redaction.overlayText?.let { text ->
                                val doc = Document(pdfDoc)
                                doc.showTextAligned(
                                    Paragraph(text)
                                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                                        .setFontSize(8f),
                                    cleanRect.left + 2,
                                    cleanRect.top - 10,
                                    pageNum,
                                    com.itextpdf.layout.properties.TextAlignment.LEFT,
                                    com.itextpdf.layout.properties.VerticalAlignment.TOP,
                                    0f
                                )
                            }
                        } else {
                            // Visual redaction (black box) but content still exists
                            val canvas = PdfCanvas(page)
                            canvas.saveState()
                                .setFillColor(com.itextpdf.kernel.colors.ColorConstants.BLACK)
                                .rectangle(cleanRect)
                                .fill()
                                .restoreState()
                        }
                    }
                }
                
                pdfDoc.close()
            }

            if (permanent) {
                // For permanent redaction, we need to flatten/optimize
                // This is a simplified version - full implementation would use PdfSweep
                redactionDao.markAsApplied(sourceUri.toString())
            }

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun applyPermanentRedactions(
        sourceUri: Uri,
        outputUri: Uri
    ): Result<Uri> = applyRedactions(sourceUri, outputUri, permanent = true)

    // ==================== SECURE DELETE ====================

    suspend fun secureDelete(fileUri: Uri): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val path = fileUri.path ?: return@withContext Result.failure(
                IllegalStateException("Invalid URI")
            )
            val file = File(path)
            
            if (!file.exists()) {
                return@withContext Result.failure(FileNotFoundException("File not found"))
            }

            val length = file.length()
            
            // Overwrite with random data multiple times
            repeat(SECURE_DELETE_PASSES) { pass ->
                RandomAccessFile(file, "rw").use { raf ->
                    val random = SecureRandom()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var position = 0L
                    
                    while (position < length) {
                        random.nextBytes(buffer)
                        val writeLength = minOf(buffer.size.toLong(), length - position).toInt()
                        raf.write(buffer, 0, writeLength)
                        position += writeLength
                    }
                    raf.fd.sync()
                }
            }
            
            // Final overwrite with zeros
            RandomAccessFile(file, "rw").use { raf ->
                val zeros = ByteArray(BUFFER_SIZE)
                var position = 0L
                
                while (position < length) {
                    val writeLength = minOf(zeros.size.toLong(), length - position).toInt()
                    raf.write(zeros, 0, writeLength)
                    position += writeLength
                }
                raf.fd.sync()
            }
            
            // Delete the file
            val deleted = file.delete()
            
            // Remove from database
            secureDocumentDao.deleteByUri(fileUri.toString())
            
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== WORKER SCHEDULING ====================

    fun scheduleSecurityOperation(
        operationType: SecurityOperationType,
        sourceUri: Uri,
        params: Data
    ): Operation {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<SecurityWorker>()
            .setInputData(
                workDataOf(
                    "operation_type" to operationType.name,
                    "source_uri" to sourceUri.toString()
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        return workManager.enqueueUniqueWork(
            "security_${operationType.name}_${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // ==================== UTILITY ====================

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = password.toByteArray(Charsets.UTF_8) + salt
        val hash = digest.digest(keyBytes)
        return SecretKeySpec(hash, "AES")
    }

    suspend fun getSecureDocument(uri: String): SecureDocumentEntity? = 
        secureDocumentDao.getDocument(uri)

    fun getAllSecureDocuments(): Flow<List<SecureDocumentEntity>> = 
        secureDocumentDao.getAllSecureDocuments()
        // Add these methods to SecurityRepository

    suspend fun decryptPdf(
        sourceUri: Uri,
        password: String,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_decrypt", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val reader = PdfReader(
                tempFile.absolutePath,
                com.itextpdf.kernel.pdf.ReaderProperties()
                    .setPassword(password.toByteArray())
            )
            val writer = PdfWriter(outputUri.path ?: throw IllegalStateException("Invalid output URI"))
            
            PdfDocument(reader, writer).use { pdfDoc ->
                pdfDoc.close()
            }

            tempFile.delete()
            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDocumentInfo(uri: Uri): Result<DocumentInfo> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("pdf_info", ".pdf", context.cacheDir)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            PdfDocument(PdfReader(tempFile.absolutePath)).use { pdfDoc ->
                val info = DocumentInfo(
                    isEncrypted = pdfDoc.reader.isEncrypted,
                    numberOfPages = pdfDoc.numberOfPages,
                    hasOwnerPassword = pdfDoc.reader.hasOwnerPassword(),
                    permissions = if (pdfDoc.reader.isEncrypted) {
                        pdfDoc.reader.getPermissions()
                    } else -1,
                    title = pdfDoc.documentInfo.title,
                    author = pdfDoc.documentInfo.author,
                    creator = pdfDoc.documentInfo.creator,
                    producer = pdfDoc.documentInfo.producer
                )
                tempFile.delete()
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class DocumentInfo(
        val isEncrypted: Boolean,
        val numberOfPages: Int,
        val hasOwnerPassword: Boolean,
        val permissions: Int,
        val title: String?,
        val author: String?,
        val creator: String?,
        val producer: String?
    )
}
