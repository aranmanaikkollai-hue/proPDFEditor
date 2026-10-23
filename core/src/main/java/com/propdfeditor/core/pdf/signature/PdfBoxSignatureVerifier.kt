package com.propdfeditor.core.pdf.signature

import com.propdfeditor.core.pdf.signature.PdfSignatureEngine.SignatureVerificationResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessable
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.util.Selector
import org.bouncycastle.util.Store
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Signature verification with PDFBox-Android (to find the signature fields) and Bouncy Castle (CMS),
 * no iText. Produces the same [SignatureVerificationResult] as [PdfSignatureEngine.verifyFile].
 *
 * What "valid" means here (same as the iText engine): the detached CMS signature verifies against the
 * signer certificate carried in the signature AND the signed message digest equals the digest of the
 * document bytes in the `/ByteRange`. It says nothing about whether the signer is trusted: chain
 * validation and revocation are not evaluated and [SignatureVerificationResult.signerTrustVerified]
 * is always false.
 *
 * The `/Contents` bytes are read from the file bytes in the byte-range **gap**, not from the parsed
 * dictionary, so the signature that is verified is exactly the one the byte range excludes. A byte
 * range that does not start at 0, lies outside the file, overlaps, or whose gap is not a
 * `<hex>` string is reported as "could not be read", never as valid.
 *
 * The signed bytes are streamed from the file (no full-document copy in memory).
 */
object PdfBoxSignatureVerifier {

    private const val MAX_GAP_BYTES = 4 * 1024 * 1024
    private const val DAMAGED = "The document was changed after signing, or the signature is damaged."
    private const val UNREADABLE = "The signature data could not be read."

    private val provider by lazy { BouncyCastleProvider() }
    private val algorithmNames by lazy { DefaultAlgorithmNameFinder() }

    private class Field(
        val name: String,
        val byteRange: IntArray?,
        val reason: String?,
        val location: String?,
        val dictionaryDate: Date?
    )

    /** @throws Exception if the file cannot be opened as a PDF (callers map this to "unreadable document"). */
    fun verify(file: File): List<SignatureVerificationResult> {
        val fields = PDDocument.load(file).use { doc ->
            doc.signatureFields.mapNotNull { f ->
                val sig = f.signature ?: return@mapNotNull null
                Field(f.fullyQualifiedName, sig.byteRange, sig.reason, sig.location, sig.signDate?.time)
            }
        }.sortedBy { f -> f.byteRange?.takeIf { it.size == 4 }?.let { it[2].toLong() + it[3] } ?: Long.MAX_VALUE }

        val length = file.length()
        return fields.map { verifyOne(file, length, it) }
    }

    private fun verifyOne(file: File, fileLength: Long, field: Field): SignatureVerificationResult {
        fun unreadable(whole: Boolean = false) = SignatureVerificationResult(
            signatureFieldName = field.name, isValid = false, signerName = null, issuerName = null, signDate = null,
            reason = field.reason, location = field.location, hashAlgorithm = null, coversWholeDocument = whole,
            isTimestamped = false, failureReason = UNREADABLE
        )

        val br = field.byteRange
        if (br == null || br.size != 4) return unreadable()
        val a = br[0].toLong(); val b = br[1].toLong(); val c = br[2].toLong(); val d = br[3].toLong()
        // Structure of a trustworthy byte range: starts at 0, a gap after the first part, second part inside the file.
        if (a != 0L || b <= 0L || c <= b || d < 0L || c + d > fileLength || c - b > MAX_GAP_BYTES) return unreadable()
        val whole = c + d == fileLength

        return try {
            val der = readContents(file, b, c) ?: return unreadable(whole)
            val contentInfo = ContentInfo.getInstance(ASN1InputStream(der).use { it.readObject() })
            val cms = CMSSignedData(ByteRangeContent(file, b, c, d), contentInfo)
            val signers = cms.signerInfos.signers.map { it as SignerInformation }
            if (signers.isEmpty()) return unreadable(whole)

            val first = signers[0]
            val cert = certificateFor(cms, first) ?: return unreadable(whole)

            val integrity = try {
                signers.all { signer ->
                    val signerCert = certificateFor(cms, signer) ?: return unreadable(whole)
                    signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(signerCert))
                }
            } catch (e: CMSException) {
                false // a changed document makes Bouncy Castle throw (digest mismatch)
            }

            val signDate = signingTime(first) ?: field.dictionaryDate
            val validAtSigning: Boolean? = if (signDate != null) {
                try { cert.checkValidity(signDate); true } catch (e: Exception) { false }
            } else {
                null
            }
            SignatureVerificationResult(
                signatureFieldName = field.name,
                isValid = integrity,
                signerName = cert.subjectX500Principal.name,
                issuerName = cert.issuerX500Principal.name,
                signDate = signDate,
                reason = field.reason,
                location = field.location,
                hashAlgorithm = algorithmNames.getAlgorithmName(first.digestAlgorithmID),
                coversWholeDocument = whole,
                isTimestamped = first.unsignedAttributes?.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken) != null,
                signerCertificateValidAtSigningTime = validAtSigning,
                failureReason = if (integrity) null else DAMAGED
            )
        } catch (e: Exception) {
            unreadable(whole)
        }
    }

    /** `/Contents` hex string from the byte-range gap [from, to), or null if the gap is not `<hex>`. */
    private fun readContents(file: File, from: Long, to: Long): ByteArray? {
        val gap = ByteArray((to - from).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            raf.readFully(gap)
        }
        if (gap.size < 2 || gap.first() != '<'.code.toByte() || gap.last() != '>'.code.toByte()) return null
        val hex = StringBuilder(gap.size)
        for (i in 1 until gap.size - 1) {
            val ch = gap[i].toInt().toChar()
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue
            if (Character.digit(ch, 16) < 0) return null
            hex.append(ch)
        }
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun certificateFor(cms: CMSSignedData, signer: SignerInformation): X509Certificate? {
        val store = cms.certificates as Store<X509CertificateHolder>
        val holder = store.getMatches(signer.sid as Selector<X509CertificateHolder>).firstOrNull() ?: return null
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
    }

    private fun signingTime(signer: SignerInformation): Date? {
        val attribute = signer.signedAttributes?.get(CMSAttributes.signingTime) ?: return null
        return try {
            Time.getInstance(attribute.attrValues.getObjectAt(0)).date
        } catch (e: Exception) {
            null
        }
    }

    /** The bytes of the file outside the signature gap: [0, b) followed by [c, c + d), streamed. */
    private class ByteRangeContent(
        private val file: File,
        private val firstLength: Long,
        private val secondStart: Long,
        private val secondLength: Long
    ) : CMSProcessable {
        override fun getContent(): Any = this

        override fun write(out: OutputStream) {
            RandomAccessFile(file, "r").use { raf ->
                copy(raf, 0L, firstLength, out)
                copy(raf, secondStart, secondLength, out)
            }
        }

        private fun copy(raf: RandomAccessFile, start: Long, length: Long, out: OutputStream) {
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw java.io.EOFException("byte range exceeds file")
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
    }
}
