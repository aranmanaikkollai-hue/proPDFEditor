package com.propdfeditor.core.pdf.signature

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdfeditor.core.pdf.signature.PdfSignatureEngine.SignatureVerificationResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/**
 * [PdfBoxSignatureVerifier] (PDFBox-Android + Bouncy Castle, no iText) against
 *  - the committed fixtures that were produced WITHOUT iText or PDFBox and independently validated with
 *    Poppler pdfsig and openssl (tools/signature_fixtures);
 *  - the existing iText-based [PdfSignatureEngine.verifyFile] (parity, while iText is still in the module);
 *  - signatures made by [PdfBoxCmsSigner];
 *  - malformed byte ranges.
 *
 * "valid" means integrity only; trust is never asserted. Android instrumented test; NOT RUN in the
 * authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxSignatureVerifierInstrumentedTest {

    private lateinit var context: Context
    private lateinit var testAssets: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        testAssets = instrumentation.context
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "pbverify_" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String): File =
        File(dir, name).also { f -> f.writeBytes(testAssets.assets.open("signature_fixtures/$name").use { it.readBytes() }) }

    private fun itext(file: File): List<SignatureVerificationResult> = PdfSignatureEngine(context).verifyFileWithIText(file)

    private class Identity(val key: java.security.PrivateKey, val cert: X509Certificate)

    private fun identity(commonName: String, validFromDaysAgo: Long = 2, validForDays: Long = 30): Identity {
        val bc = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("RSA", bc).apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val subject = X500Name("CN=$commonName,O=ProPDF Verifier Test")
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - validFromDaysAgo * day), Date(now + validForDays * day), subject, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private))
        return Identity(pair.private, JcaX509CertificateConverter().setProvider(bc).getCertificate(holder))
    }

    private fun samplePdf(name: String): File {
        val f = File(dir, name)
        PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle.A4)); doc.save(f) }
        return f
    }

    // ------------------------------------------------------------------ fixtures

    @Test
    fun validFixture_isValid_coversWholeDocument_andTrustIsNeverClaimed() {
        val r = PdfBoxSignatureVerifier.verify(fixture("valid_signed.pdf")).single()
        assertEquals("Signature1", r.signatureFieldName)
        assertTrue(r.isValid)
        assertTrue(r.coversWholeDocument)
        assertNull(r.failureReason)
        assertTrue(r.signerName.orEmpty().contains("ProPDF Test Signer"))
        assertNotNull(r.signDate)
        assertFalse(r.isTimestamped)
        assertFalse("trust is never evaluated", r.signerTrustVerified)
    }

    @Test
    fun tamperedFixture_isInvalid_withAReason() {
        val r = PdfBoxSignatureVerifier.verify(fixture("tampered_signed.pdf")).single()
        assertFalse(r.isValid)
        assertNotNull(r.failureReason)
    }

    @Test
    fun appendedFixture_staysValid_butNoLongerCoversTheWholeDocument() {
        val r = PdfBoxSignatureVerifier.verify(fixture("appended_after_sign.pdf")).single()
        assertTrue(r.isValid)
        assertFalse(r.coversWholeDocument)
    }

    @Test
    fun unsignedAndVisualOnlyFixtures_haveNoCryptographicSignatures() {
        assertTrue(PdfBoxSignatureVerifier.verify(fixture("unsigned.pdf")).isEmpty())
        assertTrue(PdfBoxSignatureVerifier.verify(fixture("visual_signature.pdf")).isEmpty())
    }

    @Test
    fun garbageFile_isAnError_notAnEmptyList() {
        val junk = File(dir, "junk.pdf").apply { writeText("this is not a pdf") }
        try {
            PdfBoxSignatureVerifier.verify(junk)
            fail("an unreadable file must not be reported as 'no signatures'")
        } catch (e: Exception) {
            // expected
        }
    }

    // ------------------------------------------------------------------ parity with the iText engine

    @Test
    fun parityWithTheITextEngine_onEveryFixture() {
        for (name in listOf("valid_signed.pdf", "tampered_signed.pdf", "appended_after_sign.pdf", "unsigned.pdf", "visual_signature.pdf")) {
            val f = fixture(name)
            val expected = itext(f)
            val actual = PdfBoxSignatureVerifier.verify(f)
            assertEquals("$name: signature count", expected.size, actual.size)
            for ((e, a) in expected.zip(actual)) {
                assertEquals("$name: field", e.signatureFieldName, a.signatureFieldName)
                assertEquals("$name: isValid", e.isValid, a.isValid)
                assertEquals("$name: coversWholeDocument", e.coversWholeDocument, a.coversWholeDocument)
                assertEquals("$name: signerName", e.signerName, a.signerName)
                assertEquals("$name: issuerName", e.issuerName, a.issuerName)
                assertEquals("$name: reason", e.reason, a.reason)
                assertEquals("$name: location", e.location, a.location)
                assertEquals("$name: isTimestamped", e.isTimestamped, a.isTimestamped)
                assertEquals("$name: valid at signing time", e.signerCertificateValidAtSigningTime, a.signerCertificateValidAtSigningTime)
                assertEquals("$name: signDate", e.signDate?.time?.div(1000), a.signDate?.time?.div(1000))
                assertEquals("$name: failure present", e.failureReason == null, a.failureReason == null)
            }
        }
    }

    // ------------------------------------------------------------------ signatures made by the spike signer

    @Test
    fun signaturesFromPdfBoxCmsSigner_verify_inOrder_andOnlyTheNewestCoversTheWholeFile() {
        val one = identity("Verifier One")
        val two = identity("Verifier Two")
        val first = File(dir, "first.pdf").also {
            PdfBoxCmsSigner.signIncremental(samplePdf("in.pdf"), it, one.key, listOf(one.cert), "One", "reason one", "place one")
        }
        val second = File(dir, "second.pdf").also {
            PdfBoxCmsSigner.signIncremental(first, it, two.key, listOf(two.cert), "Two", "reason two", "place two")
        }
        val results = PdfBoxSignatureVerifier.verify(second)
        assertEquals(2, results.size)
        assertTrue(results.all { it.isValid })
        assertTrue(results[0].signerName.orEmpty().contains("Verifier One"))
        assertTrue(results[1].signerName.orEmpty().contains("Verifier Two"))
        assertEquals("reason one", results[0].reason)
        assertEquals("place two", results[1].location)
        assertFalse(results[0].coversWholeDocument)
        assertTrue(results[1].coversWholeDocument)
        assertEquals(true, results[1].signerCertificateValidAtSigningTime)
        assertFalse(results.any { it.signerTrustVerified })
    }

    @Test
    fun signatureMadeWithAnExpiredCertificate_isIntact_butFlaggedNotValidAtSigningTime() {
        val expired = identity("Expired", validFromDaysAgo = 40, validForDays = -10)
        val out = File(dir, "expired.pdf").also {
            PdfBoxCmsSigner.signIncremental(samplePdf("in.pdf"), it, expired.key, listOf(expired.cert))
        }
        val r = PdfBoxSignatureVerifier.verify(out).single()
        assertTrue("integrity is independent of certificate validity", r.isValid)
        assertEquals(false, r.signerCertificateValidAtSigningTime)
    }

    @Test
    fun modifyingASignedPdfBoxDocument_isDetected() {
        val id = identity("Tamper")
        val out = File(dir, "t.pdf").also { PdfBoxCmsSigner.signIncremental(samplePdf("in.pdf"), it, id.key, listOf(id.cert)) }
        val bytes = out.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("%PDF-1.") + 7
        bytes[at] = if (bytes[at] == '6'.code.toByte()) '5'.code.toByte() else '6'.code.toByte()
        val tampered = File(dir, "t2.pdf").also { it.writeBytes(bytes) }
        val r = PdfBoxSignatureVerifier.verify(tampered).single()
        assertFalse(r.isValid)
        assertNotNull(r.failureReason)
    }

    // ------------------------------------------------------------------ malformed byte ranges are never "valid"

    /**
     * Rewrites the `/ByteRange [a b c d]` text with the same character count (numbers are zero-padded to
     * their original width, which is a valid PDF integer), so every other offset in the file stays correct.
     */
    private fun withByteRange(source: File, change: (IntArray, Long) -> IntArray): File {
        val bytes = source.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val m = Regex("/ByteRange\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*\\]").find(text)!!
        val widths = (1..4).map { m.groupValues[it].length }
        val old = (1..4).map { m.groupValues[it].toInt() }.toIntArray()
        val updated = change(old, bytes.size.toLong())
        val body = updated.mapIndexed { i, v -> v.toString().padStart(widths[i], '0') }.joinToString(" ")
        val replacement = "/ByteRange [$body]".padEnd(m.value.length)
        require(replacement.length == m.value.length) { "replacement would change the file length" }
        val out = text.replaceRange(m.range, replacement)
        return File(dir, "br_" + UUID.randomUUID() + ".pdf").also { it.writeBytes(out.toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test
    fun byteRangeNotStartingAtZero_isUnreadable_notValid() {
        val f = withByteRange(fixture("valid_signed.pdf")) { r, _ -> intArrayOf(1, r[1], r[2], r[3]) }
        val r = PdfBoxSignatureVerifier.verify(f).single()
        assertFalse(r.isValid)
        assertNotNull(r.failureReason)
    }

    @Test
    fun byteRangeBeyondTheFile_isUnreadable_notValid() {
        val f = withByteRange(fixture("valid_signed.pdf")) { r, size ->
            val huge = "9".repeat(r[2].toString().length).toInt()
            assertTrue("test precondition: the changed range must lie beyond the file", huge.toLong() + r[3] > size)
            intArrayOf(r[0], r[1], huge, r[3])
        }
        assertFalse(PdfBoxSignatureVerifier.verify(f).single().isValid)
    }

    @Test
    fun byteRangeWhoseGapIsNotAHexString_isUnreadable_notValid() {
        // move the gap start one byte to the right: the gap then no longer begins with '<'
        val f = withByteRange(fixture("valid_signed.pdf")) { r, _ -> intArrayOf(r[0], r[1] + 1, r[2], r[3]) }
        assertFalse(PdfBoxSignatureVerifier.verify(f).single().isValid)
    }

    @Test
    fun byteRangeThatDropsSignedBytes_isNotValid() {
        // a gap that starts earlier than the real Contents hides signed bytes from the digest
        val f = withByteRange(fixture("valid_signed.pdf")) { r, _ -> intArrayOf(r[0], r[1] - 8, r[2], r[3]) }
        assertFalse(PdfBoxSignatureVerifier.verify(f).single().isValid)
    }
}
