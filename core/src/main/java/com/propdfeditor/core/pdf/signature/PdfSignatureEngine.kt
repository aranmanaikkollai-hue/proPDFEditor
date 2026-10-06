package com.propdfeditor.core.pdf.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.signatures.BouncyCastleDigest
import com.itextpdf.signatures.DigestAlgorithms
import com.itextpdf.signatures.PdfSignatureAppearance
import com.itextpdf.signatures.PdfSigner
import com.itextpdf.signatures.PrivateKeySignature
import com.itextpdf.signatures.SignatureUtil
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two deliberately separate capabilities:
 *
 *  1. VISUAL SIGNATURE ([applyVisualSignature]) - an image stamped on a page. It is NOT a digital signature:
 *     no certificate, no CMS, no ByteRange. Implemented with PDFBox in [PdfBoxVisualSignatureEngine].
 *
 *  2. CRYPTOGRAPHIC SIGNATURE ([applyDigitalSignature], [verifySignatures]) - a detached CMS/PKCS#7 signature over
 *     the document bytes (ByteRange), written as an incremental update. This part still uses iText 7.2.5 +
 *     BouncyCastle. PDFBOX MIGRATION NOT VERIFIED: it was intentionally NOT moved to PDFBox Android, see
 *     PHASE_5_DIGITAL_SIGNATURE_REPORT.md. It is kept in this one isolated class.
 *
 * Private keys: loaded from the app-private PKCS#12 file only for the duration of one call, never logged,
 * never copied, never written anywhere. Password char arrays are wiped after use.
 */
@Singleton
class PdfSignatureEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SIGNATURE_FIELD_NAME_PREFIX = "Signature_"
        const val DEFAULT_HASH_ALGORITHM = DigestAlgorithms.SHA256

        init {
            // No-op on Android (a platform provider named "BC" already exists); kept for non-Android runs.
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private val visualEngine by lazy { PdfBoxVisualSignatureEngine(context) }

    // ================================================================== 1. VISUAL SIGNATURE

    /**
     * Stamps [signatureBitmap] on [pageNumber] (1-based) inside [rect] (DISPLAY space: points, top-left origin).
     * VISUAL ONLY - this does not sign the document cryptographically. Refuses encrypted and already-signed PDFs.
     */
    suspend fun applyVisualSignature(
        inputUri: Uri,
        outputFile: File,
        signatureBitmap: Bitmap,
        pageNumber: Int,
        rect: RectF,
        opacity: Float = 1.0f
    ): Result<Unit> = withContext(Dispatchers.IO) {
        attempt { visualEngine.stamp(inputUri, outputFile, signatureBitmap, pageNumber, rect, opacity) }
    }

    // ================================================================== 2. CRYPTOGRAPHIC SIGNATURE

    /**
     * Applies a detached CMS (PKCS#7) digital signature as an incremental update. [signatureBitmap], when given,
     * is only the visible appearance of the signature field; the cryptographic signature does not depend on it.
     * [rect] is in DISPLAY space (points, top-left origin).
     *
     * Existing signatures are preserved (the file is extended, never rewritten). The output is re-opened and
     * checked before it is published; a failed check leaves no output file.
     */
    suspend fun applyDigitalSignature(
        inputUri: Uri,
        outputFile: File,
        signatureBitmap: Bitmap?,
        pageNumber: Int,
        rect: RectF,
        keystorePath: String,
        keystorePassword: String,
        alias: String,
        keyPassword: String,
        hashAlgorithm: String = DEFAULT_HASH_ALGORITHM,
        reason: String = "Document signed digitally",
        location: String = "",
        contact: String = "",
        timestampUrl: String? = null
    ): Result<SignatureResult> = withContext(Dispatchers.IO) { attempt {
        if (!timestampUrl.isNullOrBlank()) {
            throw SignatureEngineException.Unsupported("Trusted timestamping is not supported yet.")
        }
        PDFBoxResourceLoader.init(context.applicationContext)

        val staged = File.createTempFile("sign_in_", ".pdf", context.cacheDir)
        val parent = outputFile.absoluteFile.parentFile ?: context.cacheDir
        val partial = File(parent, outputFile.name + ".part")
        val storeChars = keystorePassword.toCharArray()
        val keyChars = keyPassword.toCharArray()
        var partialStream: FileOutputStream? = null
        try {
            stage(inputUri, staged)
            val geometry = readGeometry(staged, pageNumber)
            val widget = try {
                VisualSignatureGeometry.userRect(
                    geometry.left, geometry.bottom, geometry.right, geometry.top, geometry.rotation,
                    rect.left, rect.top, rect.right, rect.bottom
                )
            } catch (e: IllegalArgumentException) {
                throw SignatureEngineException.InvalidPlacement(e.message ?: "Signature area is not valid")
            }

            // ---- key material: loaded here, used once, never logged or copied
            val keyStore = KeyStore.getInstance("PKCS12")
            try {
                FileInputStream(keystorePath).use { keyStore.load(it, storeChars) }
            } catch (e: IOException) {
                throw SignatureEngineException.KeystoreProblem(
                    "The certificate password is incorrect or the certificate file is damaged.", e
                )
            } catch (e: Exception) {
                throw SignatureEngineException.KeystoreProblem("The certificate file could not be opened.", e)
            }
            val keyAlias = resolveKeyAlias(keyStore, alias)
            val privateKey = try {
                keyStore.getKey(keyAlias, keyChars) as? PrivateKey
            } catch (e: Exception) {
                throw SignatureEngineException.KeystoreProblem("The signing key could not be unlocked.", e)
            } ?: throw SignatureEngineException.KeystoreProblem("The selected certificate has no signing key.")
            val chain = keyStore.getCertificateChain(keyAlias)
            val leaf = chain?.firstOrNull() as? X509Certificate
                ?: throw SignatureEngineException.KeystoreProblem("The selected certificate has no certificate chain.")
            try {
                leaf.checkValidity()
            } catch (e: CertificateExpiredException) {
                throw SignatureEngineException.CertificateProblem("This certificate has expired and cannot be used to sign.")
            } catch (e: CertificateNotYetValidException) {
                throw SignatureEngineException.CertificateProblem("This certificate is not valid yet and cannot be used to sign.")
            }

            val fieldName = "$SIGNATURE_FIELD_NAME_PREFIX${System.currentTimeMillis()}"
            val signedAt = Date()
            try {
                partialStream = FileOutputStream(partial)
                // Append mode: the original bytes stay untouched and any earlier signature stays valid.
                val signer = PdfSigner(PdfReader(staged), partialStream, StampingProperties().useAppendMode())
                signer.setFieldName(fieldName)

                val appearance = signer.signatureAppearance
                appearance.setPageRect(Rectangle(widget.x, widget.y, widget.width, widget.height))
                appearance.setPageNumber(pageNumber)
                appearance.setReason(reason)
                appearance.setLocation(location)
                appearance.setContact(contact)
                if (signatureBitmap != null) {
                    val png = ByteArrayOutputStream()
                    signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                    val imageData = com.itextpdf.io.image.ImageDataFactory.create(png.toByteArray())
                    appearance.setSignatureGraphic(imageData)
                    appearance.setRenderingMode(PdfSignatureAppearance.RenderingMode.GRAPHIC)
                }

                // Provider = null on purpose. On Android the name "BC" is already taken by the platform's own
                // (stripped) BouncyCastle, which has no SHA256withRSA: asking for "BC" fails with
                // NoSuchAlgorithmException (seen in CI, Oct 2026). The default provider (Conscrypt) does RSA signing;
                // iText still builds the CMS structure with the bundled BouncyCastle classes.
                val externalSignature = PrivateKeySignature(privateKey, hashAlgorithm, null)
                signer.signDetached(
                    BouncyCastleDigest(), externalSignature, chain, null, null, null, 0,
                    PdfSigner.CryptoStandard.CMS
                )
            } catch (e: SignatureEngineException) {
                throw e
            } catch (e: Exception) {
                throw SignatureEngineException.SigningFailed(cause = e)
            } finally {
                try { partialStream?.close() } catch (_: Exception) { }
            }

            // ---- post-write check (SELF-CHECK by the same library; independent verification is a test-time activity)
            val checked = try {
                verifyFile(partial)
            } catch (e: Exception) {
                throw SignatureEngineException.OutputVerificationFailed("output cannot be re-read")
            }
            val mine = checked.firstOrNull { it.signatureFieldName == fieldName }
                ?: throw SignatureEngineException.OutputVerificationFailed("new signature field not found")
            if (!mine.isValid) throw SignatureEngineException.OutputVerificationFailed("new signature does not verify")
            if (checked.any { !it.isValid }) {
                throw SignatureEngineException.OutputVerificationFailed("an earlier signature no longer verifies")
            }

            publish(partial, outputFile)
            SignatureResult(
                success = true,
                signedAt = signedAt,
                certificateSubject = leaf.subjectX500Principal.name,
                hashAlgorithm = hashAlgorithm,
                fieldName = fieldName,
                integrityVerified = true
            )
        } finally {
            storeChars.fill('\u0000')
            keyChars.fill('\u0000')
            try { partialStream?.close() } catch (_: Exception) { }
            staged.delete()
            if (partial.exists()) partial.delete()
        }
    } }

    /**
     * Reads every signature in the PDF and checks it. A signature is reported [SignatureVerificationResult.isValid]
     * only when the CMS signature verifies and the signed digest matches the document bytes in its ByteRange.
     * It does NOT mean the signer is trusted: certificate-chain trust and revocation are not evaluated
     * ([SignatureVerificationResult.signerTrustVerified] is always false).
     *
     * Throws [SignatureEngineException] when the file cannot be read - an unreadable file is never reported as
     * "no signatures".
     */
    suspend fun verifySignatures(pdfUri: Uri): List<SignatureVerificationResult> = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("verify_", ".pdf", context.cacheDir)
        try {
            stage(pdfUri, staged)
            try {
                verifyFile(staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SignatureEngineException.UnreadableDocument(e)
            }
        } finally {
            staged.delete()
        }
    }

    // ------------------------------------------------------------------ internals

    internal fun verifyFile(file: File): List<SignatureVerificationResult> {
        val results = mutableListOf<SignatureVerificationResult>()
        PdfDocument(PdfReader(file)).use { pdfDoc ->
            val util = SignatureUtil(pdfDoc)
            for (name in util.signatureNames) {
                results.add(
                    try {
                        val pkcs7 = util.readSignatureData(name)
                        val integrity = pkcs7.verifySignatureIntegrityAndAuthenticity()
                        val signDate = pkcs7.signDate?.time
                        val cert = pkcs7.signingCertificate as? X509Certificate
                        val validAtSigning: Boolean? = if (cert != null && signDate != null) {
                            try { cert.checkValidity(signDate); true } catch (e: Exception) { false }
                        } else {
                            null
                        }
                        SignatureVerificationResult(
                            signatureFieldName = name,
                            isValid = integrity,
                            signerName = cert?.subjectX500Principal?.name,
                            issuerName = cert?.issuerX500Principal?.name,
                            signDate = signDate,
                            reason = pkcs7.reason,
                            location = pkcs7.location,
                            hashAlgorithm = pkcs7.digestAlgorithm,
                            coversWholeDocument = util.signatureCoversWholeDocument(name),
                            isTimestamped = pkcs7.timeStampToken != null,
                            signerCertificateValidAtSigningTime = validAtSigning,
                            failureReason = if (integrity) null else "The document was changed after signing, or the signature is damaged."
                        )
                    } catch (e: Exception) {
                        SignatureVerificationResult(
                            signatureFieldName = name,
                            isValid = false,
                            signerName = null,
                            issuerName = null,
                            signDate = null,
                            reason = null,
                            location = null,
                            hashAlgorithm = null,
                            coversWholeDocument = false,
                            isTimestamped = false,
                            failureReason = "The signature data could not be read."
                        )
                    }
                )
            }
        }
        return results
    }

    /** The p12 file's own alias is usually not the label the user typed; fall back to its only key entry. */
    private fun resolveKeyAlias(keyStore: KeyStore, requested: String): String {
        if (requested.isNotEmpty() && keyStore.isKeyEntry(requested)) return requested
        val keyAliases = keyStore.aliases().toList().filter { keyStore.isKeyEntry(it) }
        return when (keyAliases.size) {
            1 -> keyAliases[0]
            0 -> throw SignatureEngineException.KeystoreProblem("The selected certificate file has no signing key.")
            else -> throw SignatureEngineException.KeystoreProblem(
                "The selected certificate file contains several keys and none matches the chosen certificate."
            )
        }
    }

    private class PageGeometryInfo(
        val left: Float, val bottom: Float, val right: Float, val top: Float, val rotation: Int
    )

    /** Reads page size / rotation with PDFBox and applies the pre-sign gates (encrypted, certified, page range). */
    private fun readGeometry(file: File, pageNumber: Int): PageGeometryInfo {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
        val doc: PDDocument = try {
            PDDocument.load(file, "", memory)
        } catch (e: InvalidPasswordException) {
            throw SignatureEngineException.EncryptedDocument(e)
        } catch (e: IOException) {
            throw SignatureEngineException.UnreadableDocument(e)
        }
        try {
            if (doc.isEncrypted) throw SignatureEngineException.EncryptedDocument()
            val perms = doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
            if (perms != null && perms.containsKey(COSName.getPDFName("DocMDP"))) {
                throw SignatureEngineException.CertifiedDocument()
            }
            if (pageNumber < 1 || pageNumber > doc.numberOfPages) throw SignatureEngineException.InvalidPage()
            val page = doc.getPage(pageNumber - 1)
            val box = page.cropBox
            return PageGeometryInfo(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, page.rotation)
        } finally {
            try { doc.close() } catch (_: Exception) { }
        }
    }

    private fun stage(uri: Uri, target: File) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw SignatureEngineException.UnreadableDocument(e)
        } ?: throw SignatureEngineException.UnreadableDocument()
        input.use { src -> FileOutputStream(target).use { dst -> src.copyTo(dst) } }
        if (target.length() == 0L) throw SignatureEngineException.UnreadableDocument()
    }

    private fun publish(partial: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw SignatureEngineException.SigningFailed("The signed file could not be saved.")
        }
        if (!partial.renameTo(target)) {
            partial.inputStream().use { src -> FileOutputStream(target).use { dst -> src.copyTo(dst) } }
        }
    }

    /** runCatching that never swallows coroutine cancellation. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    // ------------------------------------------------------------------ result types

    data class SignatureResult(
        val success: Boolean,
        val signedAt: Date,
        val certificateSubject: String?,
        val hashAlgorithm: String,
        val fieldName: String? = null,
        /** True only when the freshly written file re-opened and its new signature verified (self-check). */
        val integrityVerified: Boolean = false
    )

    data class SignatureVerificationResult(
        val signatureFieldName: String,
        /** Integrity + authenticity of the CMS signature over the ByteRange. NOT a statement about signer trust. */
        val isValid: Boolean,
        val signerName: String?,
        val issuerName: String?,
        val signDate: Date?,
        val reason: String?,
        val location: String?,
        val hashAlgorithm: String?,
        val coversWholeDocument: Boolean,
        val isTimestamped: Boolean,
        /** Was the signer certificate inside its validity period at the claimed signing time (null = unknown). */
        val signerCertificateValidAtSigningTime: Boolean? = null,
        /** Certificate-chain trust and revocation are never evaluated; always false. */
        val signerTrustVerified: Boolean = false,
        val failureReason: String? = null
    )
}
