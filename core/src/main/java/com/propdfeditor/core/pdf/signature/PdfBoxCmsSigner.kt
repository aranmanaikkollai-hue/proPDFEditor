package com.propdfeditor.core.pdf.signature

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar

/**
 * SPIKE (not wired into the app, not used by any screen): incremental, detached-CMS PDF signing with
 * PDFBox-Android 2.0.27.0 and Bouncy Castle, with no iText.
 *
 * Purpose: establish, with tests and independent validators, whether this stack can replace the iText
 * signing in [PdfSignatureEngine]. Until `PdfBoxCmsSignerInstrumentedTest` and the independent
 * validation workflow (`.github/workflows/signing-validation.yml`) pass, nothing here may be
 * described as a replacement.
 *
 * What it does
 *  - loads the input with PDFBox, adds an **invisible** signature field and signature dictionary
 *    (`/Filter /Adobe.PPKLite`, `/SubFilter /adbe.pkcs7.detached`) and saves it as an **incremental
 *    update**, so every original byte stays an untouched prefix of the output and earlier signatures
 *    keep verifying;
 *  - PDFBox computes the `/ByteRange` and calls [CmsSignatureInterface] with the signed bytes, which
 *    builds a detached CMS SignedData (SHA-256 with RSA or ECDSA) with the certificate chain embedded.
 *
 * What it deliberately does NOT do (so nothing here claims more than it delivers)
 *  - visible signature appearance; timestamps (RFC 3161); OCSP/CRL/long-term validation data;
 *  - PAdES conformance (no ESSCertIDv2 attribute, no DSS); certificate-chain or trust evaluation;
 *  - signing certified documents (DocMDP) or encrypted documents: both are refused;
 *  - FieldMDP / permission handling for existing signatures beyond refusing DocMDP.
 *
 * Output handling: written to `<output>.part` and moved into place only after a successful save.
 * The move is not atomic on every file system. Errors delete the partial file.
 */
object PdfBoxCmsSigner {

    /** Space reserved for the CMS blob in the signature dictionary (bytes); fits a short chain. */
    const val DEFAULT_SIGNATURE_SIZE = 18_432

    fun signIncremental(
        input: File,
        output: File,
        privateKey: PrivateKey,
        chain: List<X509Certificate>,
        signerName: String? = null,
        reason: String? = null,
        location: String? = null,
        contactInfo: String? = null,
        signDate: Calendar = Calendar.getInstance(),
        preferredSignatureSize: Int = DEFAULT_SIGNATURE_SIZE
    ) {
        require(chain.isNotEmpty()) { "The certificate chain is empty." }
        val algorithm = when (privateKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw IllegalArgumentException("Unsupported key type: ${privateKey.algorithm}")
        }
        require(input.isFile && input.length() > 0) { "The input file is missing or empty." }

        val part = File(output.path + ".part")
        try {
            PDDocument.load(input).use { doc ->
                require(!doc.isEncrypted) { "Encrypted documents are not supported." }
                require(!doc.documentCatalog.cosObject.containsKey(COSName.PERMS)) {
                    "Certified (DocMDP) documents are not supported."
                }
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    signerName?.let { setName(it) }
                    reason?.let { setReason(it) }
                    location?.let { setLocation(it) }
                    contactInfo?.let { setContactInfo(it) }
                    setSignDate(signDate)
                }
                SignatureOptions().use { options ->
                    options.preferredSignatureSize = preferredSignatureSize
                    doc.addSignature(signature, CmsSignatureInterface(privateKey, chain, algorithm), options)
                    part.outputStream().use { doc.saveIncremental(it) }
                }
            }
            if (!part.renameTo(output)) {
                part.copyTo(output, overwrite = true)
                part.delete()
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /** Builds the detached CMS over the exact byte-range content PDFBox hands over. */
    private class CmsSignatureInterface(
        private val key: PrivateKey,
        private val chain: List<X509Certificate>,
        private val algorithm: String
    ) : SignatureInterface {

        override fun sign(content: InputStream): ByteArray {
            val digestCalculators = JcaDigestCalculatorProviderBuilder().build()
            val generator = CMSSignedDataGenerator()
            generator.addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(digestCalculators).build(contentSigner(algorithm, key), chain[0])
            )
            generator.addCertificates(JcaCertStore(chain))
            return generator.generate(StreamingContent(content), false).encoded
        }
    }

    /**
     * Platform provider first (works for Android Keystore and platform keys); Bouncy Castle only if
     * the platform provider rejects the key object.
     */
    private fun contentSigner(algorithm: String, key: PrivateKey): ContentSigner = try {
        JcaContentSignerBuilder(algorithm).build(key)
    } catch (e: OperatorCreationException) {
        JcaContentSignerBuilder(algorithm).setProvider(BouncyCastleProvider()).build(key)
    }

    /** Streams the signed bytes into the CMS generator without buffering the whole document. */
    private class StreamingContent(private val stream: InputStream) : CMSTypedData {
        override fun getContentType(): ASN1ObjectIdentifier = ASN1ObjectIdentifier(CMSObjectIdentifiers.data.id)
        override fun getContent(): Any = stream
        override fun write(out: OutputStream) {
            stream.copyTo(out)
        }
    }
}
