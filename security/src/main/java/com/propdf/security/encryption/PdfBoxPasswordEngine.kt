package com.propdf.security.encryption

import android.content.Context
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.PdfProcessingError
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Password security (encrypt, decrypt, permissions, inspection) on PDFBox Android 2.0.27.0.
 *
 * Security rules, all enforced here and covered by tests:
 *  - There is NO bypass. A password-protected PDF is only opened with a password that PDFBox accepts.
 *    Nothing like iText's `setUnethicalReading(true)` exists or may be added.
 *  - Removing protection or changing permissions requires OWNER rights ([PdfSecurityException.OwnerPasswordRequired]).
 *    A user password can open a PDF but cannot lift its restrictions.
 *  - The input file is never modified. Output goes to a sibling temp file, is reopened and checked
 *    (right encryption state, same page count, passwords behave as requested), and is renamed over
 *    the output path only after that. On any failure or cancellation the temp file is deleted.
 *  - Digitally signed PDFs are refused: rewriting them would invalidate the signature.
 *  - Passwords are never logged, stored, or put in an exception message.
 *
 * Cancellation is checked between steps; a single PDFBox load or save call itself is not interruptible.
 */
class PdfBoxPasswordEngine(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    enum class Algorithm { AES_256, AES_128, RC4_128, RC4_40 }

    /** [permissions] is an ALLOW-mask built from [PdfPermissions]; see its documentation. */
    data class EncryptionRequest(
        val userPassword: String?,
        val ownerPassword: String?,
        val permissions: Int,
        val algorithm: Algorithm
    )

    /** What a PDF says about its own protection. [permissionBits] is the file's /P value. */
    data class Inspection(
        val isEncrypted: Boolean,
        val pageCount: Int,
        val openedWithOwnerRights: Boolean,
        val permissionBits: Int,
        val revision: Int,
        val version: Int,
        val keyLengthBits: Int,
        val title: String?,
        val author: String?,
        val creator: String?,
        val producer: String?
    )

    // ------------------------------------------------------------------ public operations

    /** Encrypts an unprotected PDF. Fails with [PdfSecurityException.AlreadyEncrypted] for protected input. */
    suspend fun encrypt(input: File, output: File, request: EncryptionRequest): File {
        rejectLossyPasswords(request)
        val doc = loadUnprotected(input)
        try {
            rejectSigned(doc)
            val pages = doc.numberOfPages
            val user = request.userPassword.orEmpty()
            val owner = request.ownerPassword?.takeIf { it.isNotEmpty() } ?: randomOwnerPassword()
            val revision2 = request.algorithm == Algorithm.RC4_40
            val pValue = PdfPermissions.toPValue(request.permissions, revision2)

            val policy = StandardProtectionPolicy(owner, user, AccessPermission(pValue))
            when (request.algorithm) {
                Algorithm.AES_256 -> { policy.setEncryptionKeyLength(256); policy.setPreferAES(true) }
                Algorithm.AES_128 -> { policy.setEncryptionKeyLength(128); policy.setPreferAES(true) }
                Algorithm.RC4_128 -> { policy.setEncryptionKeyLength(128); policy.setPreferAES(false) }
                Algorithm.RC4_40 -> { policy.setEncryptionKeyLength(40); policy.setPreferAES(false) }
            }
            doc.protect(policy)
            currentCoroutineContext().ensureActive()

            return publish(output) { tmp ->
                save(doc, tmp)
                currentCoroutineContext().ensureActive()
                verifyEncrypted(tmp, pages, user, owner, request.permissions)
            }
        } finally {
            closeQuietly(doc)
        }
    }

    /**
     * Sets permissions on an unprotected PDF by encrypting it with only an owner password
     * (AES-256), so anyone can open it but only the owner can lift the restrictions.
     */
    suspend fun setPermissions(input: File, output: File, ownerPassword: String, allowMask: Int): File {
        if (ownerPassword.isEmpty()) throw PdfSecurityException.BlankOwnerPassword()
        return encrypt(input, output, EncryptionRequest(null, ownerPassword, allowMask, Algorithm.AES_256))
    }

    /**
     * Removes password protection. Requires a password that PDFBox accepts AND owner rights.
     * Unprotected input is refused with [PdfSecurityException.NotEncrypted].
     */
    suspend fun decrypt(input: File, output: File, password: String): File {
        val doc = openWithPassword(input, password)
        try {
            if (!doc.isEncrypted) throw PdfSecurityException.NotEncrypted()
            if (!doc.currentAccessPermission.isOwnerPermission) throw PdfSecurityException.OwnerPasswordRequired()
            rejectSigned(doc)
            val pages = doc.numberOfPages
            doc.setAllSecurityToBeRemoved(true)
            currentCoroutineContext().ensureActive()

            return publish(output) { tmp ->
                save(doc, tmp)
                currentCoroutineContext().ensureActive()
                verifyDecrypted(tmp, pages)
            }
        } finally {
            closeQuietly(doc)
        }
    }

    /**
     * Reads protection details. [password] is only needed when the PDF has a non-empty user password.
     * Does not modify anything.
     */
    fun inspect(input: File, password: String?): Inspection {
        val doc = openWithPassword(input, password.orEmpty())
        try {
            val encryption = doc.encryption
            val info = doc.documentInformation
            return Inspection(
                isEncrypted = doc.isEncrypted,
                pageCount = doc.numberOfPages,
                openedWithOwnerRights = !doc.isEncrypted || doc.currentAccessPermission.isOwnerPermission,
                permissionBits = if (doc.isEncrypted && encryption != null) encryption.permissions else -1,
                revision = if (doc.isEncrypted && encryption != null) encryption.revision else 0,
                version = if (doc.isEncrypted && encryption != null) encryption.version else 0,
                keyLengthBits = if (doc.isEncrypted && encryption != null) encryption.length else 0,
                title = info?.title, author = info?.author, creator = info?.creator, producer = info?.producer
            )
        } finally {
            closeQuietly(doc)
        }
    }

    /**
     * True when the file is a PDF that needs a password to open or declares an encryption dictionary.
     * Unreadable or non-PDF input returns false.
     */
    fun isEncrypted(input: File): Boolean {
        return try {
            val doc = PDDocument.load(input, "", memory())
            try { doc.isEncrypted } finally { closeQuietly(doc) }
        } catch (e: InvalidPasswordException) {
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True when [password] opens the file (at any access level). Never throws. */
    fun canOpen(input: File, password: String): Boolean {
        return try {
            closeQuietly(openWithPassword(input, password)); true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ loading

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

    private fun ensureReadable(file: File) {
        if (!file.exists() || file.length() == 0L) throw AppException.FileNotFound("PDF file is missing or empty")
    }

    /** Loads a PDF that must NOT be protected (encrypt / set permissions). */
    private fun loadUnprotected(file: File): PDDocument {
        ensureReadable(file)
        val doc = try {
            PDDocument.load(file, "", memory())
        } catch (e: InvalidPasswordException) {
            throw PdfSecurityException.AlreadyEncrypted(e)
        } catch (e: IOException) {
            throw PdfProcessingError.CorruptedFile("PDF could not be opened", e)
        }
        if (doc.isEncrypted) {
            closeQuietly(doc)
            throw PdfSecurityException.AlreadyEncrypted()
        }
        return doc
    }

    /**
     * Opens with [password]. For non-ASCII passwords a second attempt uses the password's UTF-8 bytes
     * as Latin-1 characters: PDF revisions 2-4 use a single-byte encoding, whereas the earlier iText
     * code always used UTF-8 bytes, so files written by older versions of this app still open.
     * It is the same password in both attempts; this is not a bypass.
     */
    private fun openWithPassword(file: File, password: String?): PDDocument {
        ensureReadable(file)
        val given = password.orEmpty()
        val candidates = LinkedHashSet<String>()
        candidates.add(given)
        if (given.any { it.code > 127 }) {
            candidates.add(String(given.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
        }
        var rejected: InvalidPasswordException? = null
        for (candidate in candidates) {
            try {
                return PDDocument.load(file, candidate, memory())
            } catch (e: InvalidPasswordException) {
                rejected = e
            } catch (e: IOException) {
                throw PdfProcessingError.CorruptedFile("PDF could not be opened", e)
            }
        }
        throw if (given.isEmpty()) PdfSecurityException.PasswordRequired(rejected)
        else PdfSecurityException.WrongPassword(rejected)
    }

    // ------------------------------------------------------------------ checks

    /**
     * PDF revisions 2-4 (RC4 and AES-128) store passwords in a single-byte encoding, and PDFBox
     * silently replaces characters outside ISO-8859-1 with '?'. Such a password would be stored
     * lossily and the file would also open with a substitute, so it is refused instead of weakening
     * the protection. AES-256 uses UTF-8 and has no such limit.
     */
    private fun rejectLossyPasswords(request: EncryptionRequest) {
        if (request.algorithm == Algorithm.AES_256) return
        val latin1 = Charsets.ISO_8859_1.newEncoder()
        val lossy = listOfNotNull(request.userPassword, request.ownerPassword).any { !latin1.canEncode(it) }
        if (lossy) throw PdfSecurityException.UnsupportedPassword()
    }


    private fun rejectSigned(doc: PDDocument) {
        val signed = doc.signatureDictionaries.isNotEmpty() ||
            doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
                ?.containsKey(COSName.getPDFName("DocMDP")) == true
        if (signed) throw PdfSecurityException.SignedDocument()
    }

    private fun verifyEncrypted(file: File, expectedPages: Int, user: String, owner: String, allowMask: Int) {
        val withUser = try {
            PDDocument.load(file, user, memory())
        } catch (e: Exception) {
            throw PdfSecurityException.VerificationFailed("reopen with user password failed: ${e.javaClass.simpleName}")
        }
        try {
            if (!withUser.isEncrypted) throw PdfSecurityException.VerificationFailed("output is not encrypted")
            if (withUser.numberOfPages != expectedPages) throw PdfSecurityException.VerificationFailed("page count changed")
            val stored = withUser.encryption?.permissions
                ?: throw PdfSecurityException.VerificationFailed("missing encryption dictionary")
            if (PdfPermissions.allowedBits(stored) != PdfPermissions.allowedBits(allowMask)) {
                throw PdfSecurityException.VerificationFailed("permissions were not stored as requested")
            }
        } finally {
            closeQuietly(withUser)
        }
        if (user.isNotEmpty()) {
            // A document with a user password must refuse the empty password.
            val leaked = try { PDDocument.load(file, "", memory()).also { closeQuietly(it) }; true }
            catch (e: InvalidPasswordException) { false }
            catch (e: Exception) { false }
            if (leaked) throw PdfSecurityException.VerificationFailed("opens without the user password")
        }
        val withOwner = try {
            PDDocument.load(file, owner, memory())
        } catch (e: Exception) {
            throw PdfSecurityException.VerificationFailed("owner password does not open the output")
        }
        try {
            if (!withOwner.currentAccessPermission.isOwnerPermission) {
                throw PdfSecurityException.VerificationFailed("owner password does not grant owner access")
            }
        } finally {
            closeQuietly(withOwner)
        }
    }

    private fun verifyDecrypted(file: File, expectedPages: Int) {
        val reopened = try {
            PDDocument.load(file, "", memory())
        } catch (e: Exception) {
            throw PdfSecurityException.VerificationFailed("decrypted output cannot be opened: ${e.javaClass.simpleName}")
        }
        try {
            if (reopened.isEncrypted) throw PdfSecurityException.VerificationFailed("output is still encrypted")
            if (reopened.numberOfPages != expectedPages) throw PdfSecurityException.VerificationFailed("page count changed")
        } finally {
            closeQuietly(reopened)
        }
    }

    // ------------------------------------------------------------------ output

    private fun save(doc: PDDocument, tmp: File) {
        tmp.outputStream().buffered().use { doc.save(it) }
    }

    /**
     * Runs [write] against a temp sibling of [output], then renames it over [output]. The temp file
     * is deleted on any failure or cancellation, so a partial or unverified file is never published.
     */
    private suspend fun publish(output: File, write: suspend (File) -> Unit): File {
        val dir = output.absoluteFile.parentFile ?: context.cacheDir
        val tmp = File(dir, ".${output.name}.${System.nanoTime()}.tmp")
        try {
            write(tmp)
            currentCoroutineContext().ensureActive()
            if (!tmp.exists() || tmp.length() == 0L) throw IOException("Output was not written")
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
            return output
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun closeQuietly(doc: PDDocument) {
        try { doc.close() } catch (_: Exception) { }
    }

    /** Random 192-bit owner password for "no owner password given", so restrictions stay enforceable. */
    private fun randomOwnerPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
