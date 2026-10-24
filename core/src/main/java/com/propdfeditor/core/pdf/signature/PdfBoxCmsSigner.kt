package com.propdfeditor.core.pdf.signature

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
 *  - loads the input with PDFBox, adds a signature field (invisible, or visible with an image or a short text block) and signature dictionary
 *    (`/Filter /Adobe.PPKLite`, `/SubFilter /adbe.pkcs7.detached`) and saves it as an **incremental
 *    update**, so every original byte stays an untouched prefix of the output and earlier signatures
 *    keep verifying;
 *  - PDFBox computes the `/ByteRange` and calls [CmsSignatureInterface] with the signed bytes, which
 *    builds a detached CMS SignedData (SHA-256 with RSA or ECDSA) with the certificate chain embedded.
 *
 * What it deliberately does NOT do (so nothing here claims more than it delivers)
 *  - timestamps (RFC 3161); OCSP/CRL/long-term validation data;
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

    /**
     * Where and how the signature is shown. [x], [y], [width], [height] are the widget rectangle in the
     * page's default USER space (lower-left origin, points); [rotation] is the page's /Rotate (0/90/180/270).
     * With [image] the signature shows that bitmap scaled to fit (aspect ratio kept); without it a short
     * text block (signer, date, reason, location) is drawn. Text uses the built-in Helvetica font: characters
     * outside WinAnsi are replaced by '?'. The cryptographic signature does not depend on the appearance.
     */
    class VisibleAppearance(
        val pageIndex: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rotation: Int = 0,
        val image: Bitmap? = null
    )

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
        preferredSignatureSize: Int = DEFAULT_SIGNATURE_SIZE,
        fieldName: String? = null,
        hashAlgorithm: String = "SHA256",
        visible: VisibleAppearance? = null
    ) {
        require(chain.isNotEmpty()) { "The certificate chain is empty." }
        val digest = when (hashAlgorithm.uppercase().replace("-", "")) {
            "SHA256" -> "SHA256"
            "SHA384" -> "SHA384"
            "SHA512" -> "SHA512"
            else -> throw IllegalArgumentException("Unsupported hash algorithm: $hashAlgorithm")
        }
        val algorithm = when (privateKey.algorithm.uppercase()) {
            "RSA" -> "${digest}withRSA"
            "EC", "ECDSA" -> "${digest}withECDSA"
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
                if (visible != null) {
                    require(visible.pageIndex in 0 until doc.numberOfPages) { "The signature page does not exist." }
                    require(visible.width > 0f && visible.height > 0f) { "The signature area is empty." }
                }
                SignatureOptions().use { options ->
                    options.preferredSignatureSize = preferredSignatureSize
                    visible?.let { options.page = it.pageIndex }
                    doc.addSignature(signature, CmsSignatureInterface(privateKey, chain, algorithm), options)
                    val field = doc.signatureFields.firstOrNull { it.signature?.cosObject === signature.cosObject }
                        ?: throw IllegalStateException("The new signature field was not created.")
                    fieldName?.let { field.partialName = it }
                    if (visible != null) {
                        applyVisibleAppearance(doc, field.widgets.first(), visible, chain[0], signDate, reason, location)
                    }
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

    private fun applyVisibleAppearance(
        doc: PDDocument,
        widget: com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget,
        v: VisibleAppearance,
        signer: X509Certificate,
        signDate: Calendar,
        reason: String?,
        location: String?
    ) {
        widget.rectangle = PDRectangle(v.x, v.y, v.width, v.height)
        widget.setPrinted(true) // the signature is part of the printed page

        // The form is drawn upright in DISPLAY orientation; /Matrix rotates it into the (possibly rotated) page.
        val rotation = Math.floorMod((v.rotation / 90) * 90, 360)
        val sideways = rotation == 90 || rotation == 270
        val drawWidth = if (sideways) v.height else v.width
        val drawHeight = if (sideways) v.width else v.height

        val form = PDAppearanceStream(doc)
        form.bBox = PDRectangle(drawWidth, drawHeight)
        form.resources = PDResources()
        rotationMatrix(rotation)?.let { form.cosObject.setItem(COSName.MATRIX, it) }

        PDPageContentStream(doc, form).use { cs ->
            val bitmap = v.image
            if (bitmap != null) {
                val scale = minOf(drawWidth / bitmap.width, drawHeight / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                cs.drawImage(LosslessFactory.createFromImage(doc, bitmap), (drawWidth - w) / 2f, (drawHeight - h) / 2f, w, h)
            } else {
                val lines = descriptionLines(signer, signDate, reason, location)
                val fontSize = minOf(9f, maxOf(5f, drawHeight / (lines.size + 1)))
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, fontSize)
                cs.setLeading(fontSize + 1f)
                cs.newLineAtOffset(2f, drawHeight - fontSize - 1f)
                var y = drawHeight - fontSize - 1f
                for (line in lines) {
                    if (y < 0f) break // does not fit: drop the remaining lines rather than draw outside the box
                    cs.showText(line)
                    cs.newLine()
                    y -= fontSize + 1f
                }
                cs.endText()
            }
        }
        val appearance = PDAppearanceDictionary()
        appearance.setNormalAppearance(form)
        widget.appearance = appearance
    }

    private fun rotationMatrix(rotation: Int): COSArray? {
        val values = when (rotation) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, 0f, 0f)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 0f)
            else -> return null
        }
        return COSArray().apply { setFloatArray(values) }
    }

    private fun descriptionLines(signer: X509Certificate, date: Calendar, reason: String?, location: String?): List<String> {
        val cn = X500Name(signer.subjectX500Principal.name).getRDNs(BCStyle.CN).firstOrNull()
            ?.first?.value?.let { IETFUtils.valueToString(it) } ?: signer.subjectX500Principal.name
        val lines = mutableListOf("Digitally signed by $cn")
        lines.add("Date: " + SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US).format(date.time))
        if (!reason.isNullOrBlank()) lines.add("Reason: $reason")
        if (!location.isNullOrBlank()) lines.add("Location: $location")
        return lines.map { winAnsiOnly(it) }
    }

    /** The built-in Helvetica font only encodes WinAnsi; anything else would throw, so it is replaced. */
    internal fun winAnsiOnly(text: String): String =
        text.map { if (it.code in 32..126 || it.code in 160..255) it else '?' }.joinToString("")

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
