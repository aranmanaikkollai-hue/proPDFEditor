// security/src/main/java/com/propdf/security/data/repository/SecurityRepository.kt
package com.propdf.security.data.repository

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.propdf.security.redaction.RedactionEngine
import com.propdf.security.redaction.RedactionRegion
import com.propdf.security.data.dao.RedactionDao
import com.propdf.security.data.dao.SecureDocumentDao
import com.propdf.security.data.dao.SecurityOperationDao
import com.propdf.security.data.entity.*
import com.propdf.security.encryption.PdfBoxPasswordEngine
import com.propdf.security.encryption.PdfPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
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
    private val secureDocumentDao: SecureDocumentDao
) {
    companion object {
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val BUFFER_SIZE = 8192
        private const val SECURE_DELETE_PASSES = 3
    }

    /**
     * Writes PDF output to a local cache file, then streams that file's bytes into
     * [outputUri] via the ContentResolver.
     *
     * The former iText-based operations (encrypt, redact, sanitize, strip metadata)
     * used to construct `PdfWriter(outputUri.path ?: ...)` directly against the caller's
     * output URI. That only produces a usable filesystem path for `file://` URIs. Every
     * real caller in this app obtains its output location from the
     * `ActivityResultContracts.CreateDocument(...)` SAF picker, which always hands back a
     * `content://` URI -- `.path` on those is not a real path on disk, so PdfWriter would
     * either throw immediately or silently write to the wrong place. Routing every write
     * through a local temp file + ContentResolver.openOutputStream fixes that uniformly,
     * the same way sourceUri is already read via `contentResolver.openInputStream`.
     */
    private inline fun writePdfToUri(outputUri: Uri, block: (File) -> Unit) {
        val tempOutput = File.createTempFile("pdf_out", ".pdf", context.cacheDir)
        try {
            block(tempOutput)
            context.contentResolver.openOutputStream(outputUri)?.use { out ->
                FileInputStream(tempOutput).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open output stream for $outputUri")
        } finally {
            tempOutput.delete()
        }
    }

    // ==================== PASSWORD SECURITY (PDFBox) ====================

    private val passwordEngine by lazy { PdfBoxPasswordEngine(context) }

    /**
     * Copies [sourceUri] (file:// or content://) into the private cache file [target] through the
     * ContentResolver. The caller owns and deletes [target]. Never touches `Uri.path`.
     */
    /**
     * Opens [file] with PDFBox Android, runs [block], and always closes the document. Encrypted files are
     * refused here (metadata removal and sanitising need the content readable, and no password is available);
     * the caller sees a failed Result with a plain message.
     */
    private fun withPdfBoxDocument(file: File, block: (PDDocument) -> Unit) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val doc = try {
            PDDocument.load(file, "", memory)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw IllegalStateException("This PDF is password protected. Remove the password first.", e)
        }
        try {
            if (doc.isEncrypted) {
                throw IllegalStateException("This PDF is password protected. Remove the password first.")
            }
            block(doc)
        } finally {
            try { doc.close() } catch (_: Exception) { }
        }
    }

    private fun stageSource(sourceUri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw FileNotFoundException("Source is no longer available")
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        if (target.length() == 0L) throw FileNotFoundException("Source is empty")
    }

    /**
     * Streams the finished, verified cache file [produced] to [outputUri]. Nothing is written to
     * the destination before this point, so a failed operation leaves it untouched. If the copy
     * itself fails half-way the destination is truncated so no partial PDF is left behind.
     */
    private fun publishToUri(outputUri: Uri, produced: File) {
        var opened = false
        try {
            val out = context.contentResolver.openOutputStream(outputUri, "wt")
                ?: throw IOException("Could not open the output location")
            opened = true
            out.use { o -> produced.inputStream().use { it.copyTo(o) } }
        } catch (t: Throwable) {
            if (opened) {
                try { context.contentResolver.openOutputStream(outputUri, "wt")?.close() } catch (_: Exception) { }
            }
            throw t
        }
    }

    private fun algorithmFor(type: EncryptionType): PdfBoxPasswordEngine.Algorithm = when (type) {
        EncryptionType.AES_256 -> PdfBoxPasswordEngine.Algorithm.AES_256
        EncryptionType.AES_128 -> PdfBoxPasswordEngine.Algorithm.AES_128
        EncryptionType.STANDARD_128 -> PdfBoxPasswordEngine.Algorithm.RC4_128
        EncryptionType.STANDARD_40 -> PdfBoxPasswordEngine.Algorithm.RC4_40
        // Same fallback the iText code had for any other value.
        else -> PdfBoxPasswordEngine.Algorithm.RC4_128
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
                operationDao.getOperationsForDocument(sourceUri.toString()).first()
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
        val staged = File.createTempFile("pdf_protect", ".pdf", context.cacheDir)
        val produced = File.createTempFile("pdf_protect_out", ".pdf", context.cacheDir)
        try {
            stageSource(sourceUri, staged)
            passwordEngine.encrypt(
                staged, produced,
                PdfBoxPasswordEngine.EncryptionRequest(
                    userPassword = userPassword,
                    ownerPassword = ownerPassword,
                    permissions = permissions,
                    algorithm = algorithmFor(encryptionAlgorithm)
                )
            )
            currentCoroutineContext().ensureActive()
            publishToUri(outputUri, produced)

            // Update secure document record (flags only; passwords are never stored)
            secureDocumentDao.insert(
                SecureDocumentEntity(
                    uri = outputUri.toString(),
                    encryptionType = encryptionAlgorithm,
                    hasOwnerPassword = !ownerPassword.isNullOrBlank(),
                    hasUserPassword = !userPassword.isNullOrBlank(),
                    permissions = permissions
                )
            )
            Result.success(outputUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged.delete()
            produced.delete()
        }
    }

    // ==================== PERMISSIONS ====================

    /**
     * Encrypts an unprotected PDF with only an owner password (anyone can open it; only the owner
     * can lift the restrictions). [newPermissions] is an ALLOW-mask from [PdfPermissions].
     */
    suspend fun setPermissions(
        sourceUri: Uri,
        ownerPassword: String,
        newPermissions: Int,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("pdf_perm", ".pdf", context.cacheDir)
        val produced = File.createTempFile("pdf_perm_out", ".pdf", context.cacheDir)
        try {
            stageSource(sourceUri, staged)
            passwordEngine.setPermissions(staged, produced, ownerPassword, newPermissions)
            currentCoroutineContext().ensureActive()
            publishToUri(outputUri, produced)
            Result.success(outputUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged.delete()
            produced.delete()
        }
    }

    // ==================== METADATA REMOVAL ====================

    suspend fun removeMetadata(
        sourceUri: Uri,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("pdf_meta", ".pdf", context.cacheDir)
        try {
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            writePdfToUri(outputUri) { tempOutput ->
                withPdfBoxDocument(tempFile) { doc ->
                    // A fresh, empty Info dictionary drops title, author, subject, keywords, creator,
                    // producer, creation/modification dates and any custom keys in one step.
                    doc.documentInformation = PDDocumentInformation()
                    // XMP metadata stream.
                    doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("Metadata"))
                    doc.save(tempOutput)
                }
            }

            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    // ==================== DOCUMENT SANITIZATION ====================

    suspend fun sanitizeDocument(
        sourceUri: Uri,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("pdf_sanitize", ".pdf", context.cacheDir)
        try {
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            writePdfToUri(outputUri) { tempOutput ->
                withPdfBoxDocument(tempFile) { doc ->
                    val catalog = doc.documentCatalog.cosObject
                    // JavaScript, name trees (this also drops embedded files and named destinations),
                    // open actions and additional actions.
                    for (key in listOf("Names", "OpenAction", "AA", "JavaScript", "JS", "EmbeddedFiles")) {
                        catalog.removeItem(COSName.getPDFName(key))
                    }
                    // Annotations other than links, and per-page additional actions.
                    for (page in doc.pages) {
                        val kept = page.annotations.filter { it.subtype == "Link" }
                        page.annotations = kept
                        page.cosObject.removeItem(COSName.getPDFName("AA"))
                    }
                    // Form: the widgets were removed above with the other annotations.
                    catalog.removeItem(COSName.getPDFName("AcroForm"))
                    // Document information, XMP metadata and optional-content (layer) definitions.
                    doc.documentInformation = PDDocumentInformation()
                    catalog.removeItem(COSName.getPDFName("Metadata"))
                    catalog.removeItem(COSName.getPDFName("OCProperties"))
                    doc.save(tempOutput)
                }
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

            Result.success(outputUri)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
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

    /**
     * Applies the pending redaction marks of [sourceUri] with the secure engine ([RedactionEngine] ->
     * PdfBoxRedactionEngine): affected pages are rebuilt so the covered content is removed from the file, not
     * painted over. [permanent] is kept for API compatibility and no longer changes the result: a black box
     * over recoverable content is never produced.
     *
     * On any failure the destination is not written and the pending marks are kept for a retry; marks are
     * consumed only after the output was written successfully.
     */
    suspend fun applyRedactions(
        sourceUri: Uri,
        outputUri: Uri,
        permanent: Boolean = false
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val redactions = redactionDao.getPendingRedactions(sourceUri.toString()).first()
            if (redactions.isEmpty()) {
                return@withContext Result.failure(com.propdf.security.encryption.PdfSecurityException.RedactionNoRegions())
            }
            val regions = redactions.map { RedactionRegion(it.pageNumber, it.rect, it.overlayText) }
            val produced = File.createTempFile("pdf_redact_out_", ".pdf", context.cacheDir)
            try {
                RedactionEngine(context).redactRegions(sourceUri, produced, regions).getOrThrow()
                publishToUri(outputUri, produced)
            } finally {
                produced.delete()
            }
            redactionDao.markAsApplied(sourceUri.toString())
            Result.success(outputUri)
        } catch (e: CancellationException) {
            throw e
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
            val file = resolveOverwritableFile(fileUri)

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

    /**
     * Secure delete overwrites a real file in place, so it only accepts file:// URIs that point inside this app's
     * own storage. content:// URIs (system picker, document providers) have no usable path and must never be
     * turned into a File via `uri.path`; they are rejected with an explanation instead.
     */
    private fun resolveOverwritableFile(uri: Uri): File {
        if (uri.scheme != "file") {
            throw IllegalArgumentException(
                "Secure delete only works on files stored inside this app. Documents opened through the " +
                    "system file picker cannot be overwritten, so nothing was changed."
            )
        }
        val path = uri.path ?: throw IllegalArgumentException("This file location is not valid.")
        val canonical = File(path).canonicalFile            // resolves symlinks and ".." segments
        val roots = listOfNotNull(
            context.filesDir, context.cacheDir, context.getExternalFilesDir(null), context.externalCacheDir
        ).map { it.canonicalFile }
        val inside = roots.any { root -> canonical.path.startsWith(root.path + File.separator) }
        if (!inside) {
            throw IllegalArgumentException(
                "Secure delete only works on files stored inside this app, so nothing was changed."
            )
        }
        if (canonical.exists() && !canonical.isFile) {
            throw IllegalArgumentException("Only files can be overwritten and deleted.")
        }
        return canonical
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

    /**
     * Removes password protection. [password] must be accepted by the PDF and must carry owner
     * rights; there is no bypass (see [PdfBoxPasswordEngine.decrypt]).
     */
    suspend fun decryptPdf(
        sourceUri: Uri,
        password: String,
        outputUri: Uri
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("pdf_decrypt", ".pdf", context.cacheDir)
        val produced = File.createTempFile("pdf_decrypt_out", ".pdf", context.cacheDir)
        try {
            stageSource(sourceUri, staged)
            passwordEngine.decrypt(staged, produced, password)
            currentCoroutineContext().ensureActive()
            publishToUri(outputUri, produced)
            Result.success(outputUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged.delete()
            produced.delete()
        }
    }

    /**
     * Reads protection details without modifying anything. A PDF that needs a password fails with
     * a typed password error unless [password] is supplied.
     */
    suspend fun getDocumentInfo(uri: Uri, password: String? = null): Result<DocumentInfo> = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("pdf_info", ".pdf", context.cacheDir)
        try {
            stageSource(uri, staged)
            val info = passwordEngine.inspect(staged, password)
            Result.success(
                DocumentInfo(
                    isEncrypted = info.isEncrypted,
                    numberOfPages = info.pageCount,
                    hasOwnerPassword = info.openedWithOwnerRights,
                    permissions = info.permissionBits,
                    title = info.title,
                    author = info.author,
                    creator = info.creator,
                    producer = info.producer
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staged.delete()
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
