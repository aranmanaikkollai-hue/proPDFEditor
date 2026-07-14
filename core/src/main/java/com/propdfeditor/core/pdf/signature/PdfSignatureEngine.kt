package com.propdfeditor.core.pdf.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.itextpdf.signatures.BouncyCastleDigest
import com.itextpdf.signatures.DigestAlgorithms
import com.itextpdf.signatures.PdfSignatureAppearance
import com.itextpdf.signatures.PdfSigner
import com.itextpdf.signatures.PrivateKeySignature
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.database.entity.SignatureHistoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfSignatureEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SIGNATURE_FIELD_NAME_PREFIX = "Signature_"
        const val DEFAULT_HASH_ALGORITHM = DigestAlgorithms.SHA256

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /**
     * Apply a visual signature (bitmap) to a PDF document without cryptographic signing.
     * This creates a visible signature appearance but does not add a digital certificate.
     */
    suspend fun applyVisualSignature(
        inputUri: Uri,
        outputFile: File,
        signatureBitmap: Bitmap,
        pageNumber: Int,
        rect: RectF,
        opacity: Float = 1.0f
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                val tempFile = File(context.cacheDir, "temp_sign_${System.currentTimeMillis()}.pdf")
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
                
                PdfDocument(PdfReader(tempFile), PdfWriter(outputFile)).use { pdfDoc ->
                    val page = pdfDoc.getPage(pageNumber)
                    val pageSize = page.pageSize
                    
                    // Convert Android RectF (top-left origin) to PDF coordinates (bottom-left origin)
                    val pdfRect = Rectangle(
                        rect.left,
                        pageSize.height - rect.bottom,
                        rect.right - rect.left,
                        rect.bottom - rect.top
                    )
                    
                    val canvas = PdfCanvas(page)
                    
                    // Save state
                    canvas.saveState()
                    
                    // Set opacity
                    if (opacity < 1.0f) {
                        val gState = PdfExtGState().apply {
                            setFillOpacity(opacity)
                            setStrokeOpacity(opacity)
                        }
                        canvas.setExtGState(gState)
                    }
                    
                    // Convert bitmap to PDF image
                    val stream = ByteArrayOutputStream()
                    signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val imageData = com.itextpdf.io.image.ImageDataFactory.create(stream.toByteArray())
                    val pdfImage = PdfImageXObject(imageData)
                    
                    canvas.addXObjectFittedIntoRectangle(pdfImage, pdfRect)
                    
                    // Restore state
                    canvas.restoreState()
                    
                    pdfDoc.close()
                }
                
                tempFile.delete()
                Unit
            } ?: throw IllegalArgumentException("Cannot open input PDF")
        }
    }

    /**
     * Apply a cryptographic digital signature with optional visual appearance.
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
    ): Result<SignatureResult> = withContext(Dispatchers.IO) {
        runCatching {
            val tempInput = File(context.cacheDir, "temp_in_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("Cannot open input PDF")

            // Load keystore
            val keyStore = KeyStore.getInstance("PKCS12")
            java.io.FileInputStream(keystorePath).use { stream ->
                keyStore.load(stream, keystorePassword.toCharArray())
            }

            val privateKey = keyStore.getKey(alias, keyPassword.toCharArray()) as PrivateKey
            val certChain = keyStore.getCertificateChain(alias)

            val signer = PdfSigner(
                PdfReader(tempInput),
                FileOutputStream(outputFile),
                StampingProperties()
            )

            signer.setFieldName("$SIGNATURE_FIELD_NAME_PREFIX${System.currentTimeMillis()}")

            val appearance = signer.signatureAppearance
            appearance.setPageRect(
                Rectangle(
                    rect.left,
                    rect.top,
                    rect.right - rect.left,
                    rect.bottom - rect.top
                )
            )
            appearance.setPageNumber(pageNumber)
            appearance.setReason(reason)
            appearance.setLocation(location)
            appearance.setContact(contact)

            // Create signature appearance if bitmap provided
            if (signatureBitmap != null) {
                val appearanceStream = ByteArrayOutputStream()
                signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, appearanceStream)
                val imageData = com.itextpdf.io.image.ImageDataFactory.create(appearanceStream.toByteArray())
                appearance.setSignatureGraphic(imageData)
                appearance.setRenderingMode(PdfSignatureAppearance.RenderingMode.GRAPHIC)
            }

            val signature = PrivateKeySignature(privateKey, hashAlgorithm, BouncyCastleProvider.PROVIDER_NAME)
            val digest = BouncyCastleDigest()

            signer.signDetached(
                digest,
                signature,
                certChain,
                null,
                null,
                null,
                0,
                PdfSigner.CryptoStandard.CMS
            )

            tempInput.delete()

            SignatureResult(
                success = true,
                signedAt = Calendar.getInstance().time,
                certificateSubject = (certChain?.firstOrNull() as? java.security.cert.X509Certificate)?.subjectX500Principal?.name,
                hashAlgorithm = hashAlgorithm
            )
        }
    }

    /**
     * Verify digital signatures in a PDF document.
     */
    suspend fun verifySignatures(pdfUri: Uri): List<SignatureVerificationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val results = mutableListOf<SignatureVerificationResult>()
            
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                val tempFile = File(context.cacheDir, "verify_${System.currentTimeMillis()}.pdf")
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
                
                val pdfDoc = PdfDocument(PdfReader(tempFile))
                val signatureUtil = com.itextpdf.signatures.SignatureUtil(pdfDoc)
                val names = signatureUtil.getSignatureNames()
                
                names.forEach { name ->
                    val pkcs7 = signatureUtil.readSignatureData(name)
                    val verified = pkcs7?.verifySignatureIntegrityAndAuthenticity() ?: false
                    
                    val signDate = pkcs7?.signDate?.time
                    val cert = pkcs7?.signingCertificate as? java.security.cert.X509Certificate
                    
                    results.add(
                        SignatureVerificationResult(
                            signatureFieldName = name,
                            isValid = verified,
                            signerName = cert?.subjectX500Principal?.name,
                            issuerName = cert?.issuerX500Principal?.name,
                            signDate = signDate,
                            reason = pkcs7?.reason,
                            location = pkcs7?.location,
                            hashAlgorithm = pkcs7?.digestAlgorithm,
                            coversWholeDocument = signatureUtil.signatureCoversWholeDocument(name),
                            isTimestamped = pkcs7?.timeStampToken != null
                        )
                    )
                }
                
                pdfDoc.close()
                tempFile.delete()
            }
            
            results
        }.getOrElse { emptyList() }
    }

    data class SignatureResult(
        val success: Boolean,
        val signedAt: java.util.Date,
        val certificateSubject: String?,
        val hashAlgorithm: String
    )

    data class SignatureVerificationResult(
        val signatureFieldName: String,
        val isValid: Boolean,
        val signerName: String?,
        val issuerName: String?,
        val signDate: java.util.Date?,
        val reason: String?,
        val location: String?,
        val hashAlgorithm: String?,
        val coversWholeDocument: Boolean,
        val isTimestamped: Boolean
    )
}
