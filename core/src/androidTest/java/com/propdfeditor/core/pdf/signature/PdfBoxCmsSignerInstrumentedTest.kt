package com.propdfeditor.core.pdf.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/**
 * Spike tests for [PdfBoxCmsSigner] (PDFBox-Android + Bouncy Castle, no iText).
 *
 * Independence: the PDF structure (ByteRange, Contents) is parsed straight from the file bytes with a
 * regular expression and the CMS is verified with Bouncy Castle directly, as in
 * `PdfSignatureEngineInstrumentedTest`. The files are also exported to the app's external files
 * directory (`signing-spike/`) so the manual workflow `signing-validation.yml` can check them with
 * Poppler `pdfsig`, qpdf and pyHanko. Integrity and certificate trust are reported separately:
 * every certificate here is self-signed and untrusted by construction.
 *
 * Throwaway keys are generated at run time; nothing secret is checked in. NOT RUN in the authoring
 * environment.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxCmsSignerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "cmssigner_" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private class Identity(val key: PrivateKey, val cert: X509Certificate)

    /** [bcKeys]=true generates the key pair with Bouncy Castle, false with the platform provider. */
    private fun identity(commonName: String, bcKeys: Boolean): Identity {
        val bc = BouncyCastleProvider()
        val generator = if (bcKeys) KeyPairGenerator.getInstance("RSA", bc) else KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val subject = X500Name("CN=$commonName,O=ProPDF Spike Test")
        val holder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 2 * day), Date(now + 30 * day), subject, pair.public
        ).build(certificateSigner(pair.private))
        return Identity(pair.private, JcaX509CertificateConverter().setProvider(bc).getCertificate(holder))
    }

    /** Signs the throwaway certificate: platform provider first, Bouncy Castle if it rejects the key. */
    private fun certificateSigner(key: PrivateKey): ContentSigner = try {
        JcaContentSignerBuilder("SHA256withRSA").build(key)
    } catch (e: OperatorCreationException) {
        JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider()).build(key)
    }

    private fun samplePdf(name: String, pages: Int = 2): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (i in 1..pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(72f, 720f); cs.showText("Signing spike page $i"); cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun sign(input: File, output: File, id: Identity, reason: String? = "spike test") {
        PdfBoxCmsSigner.signIncremental(input, output, id.key, listOf(id.cert), "Spike Signer", reason, "Test lab")
    }

    private class Sig(val a: Int, val b: Int, val c: Int, val d: Int, val signedBytes: ByteArray, val cms: ByteArray) {
        fun coversWholeFile(fileSize: Int) = a == 0 && c + d == fileSize
    }

    /** Every signature's ByteRange and Contents, parsed from the raw bytes (no PDF library). */
    private fun signatures(bytes: ByteArray): List<Sig> {
        val text = String(bytes, Charsets.ISO_8859_1)
        return Regex("/ByteRange\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*\\]").findAll(text).map { m ->
            val (a, b, c, d) = m.destructured
            val ai = a.toInt(); val bi = b.toInt(); val ci = c.toInt(); val di = d.toInt()
            val gap = String(bytes, bi, ci - bi, Charsets.ISO_8859_1)
            assertTrue("Contents must be a hex string", gap.startsWith("<") && gap.endsWith(">"))
            val hex = gap.substring(1, gap.length - 1)
            val der = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            Sig(ai, bi, ci, di, bytes.copyOfRange(ai, ai + bi) + bytes.copyOfRange(ci, ci + di), der)
        }.toList()
    }

    /** Integrity only (digest + signature of the signed bytes against the signer certificate). */
    private fun cmsVerifies(sig: Sig, cert: X509Certificate): Boolean {
        val obj = ASN1InputStream(sig.cms).use { it.readObject() }
        val cms = CMSSignedData(CMSProcessableByteArray(sig.signedBytes), ContentInfo.getInstance(obj))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider()).build(cert)
        val signers = cms.signerInfos.signers as Collection<*>
        if (signers.isEmpty()) return false
        return try {
            signers.all { (it as SignerInformation).verify(verifier) }
        } catch (e: CMSException) {
            false // a changed document makes Bouncy Castle throw (digest mismatch)
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun sign_producesIncrementalUpdate_originalBytesStayAsPrefix() {
        val id = identity("A", bcKeys = true)
        val input = samplePdf("in.pdf")
        val output = File(dir, "out.pdf")
        sign(input, output, id)
        val original = input.readBytes()
        val signed = output.readBytes()
        assertTrue(signed.size > original.size)
        assertArrayEquals("original bytes must be an untouched prefix", original, signed.copyOfRange(0, original.size))
    }

    @Test
    fun byteRange_isWellFormed_andCoversTheWholeFile() {
        val id = identity("A", bcKeys = true)
        val output = File(dir, "out.pdf").also { sign(samplePdf("in.pdf"), it, id) }
        val bytes = output.readBytes()
        val sigs = signatures(bytes)
        assertEquals(1, sigs.size)
        val s = sigs[0]
        assertEquals(0, s.a)
        assertTrue("gap must contain the Contents hex string", s.c > s.b)
        assertTrue("second range must end at end of file", s.coversWholeFile(bytes.size))
    }

    @Test
    fun signature_verifiesIndependentlyWithBouncyCastle_forBothKeyProviders() {
        for (bcKeys in listOf(true, false)) {
            val id = identity("Key-${if (bcKeys) "BC" else "platform"}", bcKeys)
            val out = File(dir, "out_$bcKeys.pdf").also { sign(samplePdf("in_$bcKeys.pdf"), it, id) }
            val sig = signatures(out.readBytes()).single()
            assertTrue("BC must verify (bcKeys=$bcKeys)", cmsVerifies(sig, id.cert))
        }
    }

    @Test
    fun signature_doesNotVerifyAgainstAnotherCertificate() {
        val signer = identity("Signer", bcKeys = true)
        val other = identity("Other", bcKeys = true)
        val out = File(dir, "out.pdf").also { sign(samplePdf("in.pdf"), it, signer) }
        assertFalse(cmsVerifies(signatures(out.readBytes()).single(), other.cert))
    }

    @Test
    fun tampering_insideTheSignedRange_isDetected() {
        val id = identity("A", bcKeys = true)
        val out = File(dir, "out.pdf").also { sign(samplePdf("in.pdf"), it, id) }
        val bytes = out.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("%PDF-1.") + 7
        bytes[at] = if (bytes[at] == '6'.code.toByte()) '5'.code.toByte() else '6'.code.toByte()
        assertFalse("tampered file must not verify", cmsVerifies(signatures(bytes).single(), id.cert))
    }

    @Test
    fun appendingAfterSigning_keepsIntegrityOfTheSignedRange_butIsNoLongerWholeFile() {
        val id = identity("A", bcKeys = true)
        val out = File(dir, "out.pdf").also { sign(samplePdf("in.pdf"), it, id) }
        val bytes = out.readBytes() + "\n%% appended after signing\n".toByteArray(Charsets.ISO_8859_1)
        val sig = signatures(bytes).single()
        assertTrue("the signed range itself is unchanged", cmsVerifies(sig, id.cert))
        assertFalse("but it no longer covers the whole file", sig.coversWholeFile(bytes.size))
    }

    @Test
    fun secondSignature_keepsTheFirstOneValid_andOnlyTheNewestCoversTheWholeFile() {
        val first = identity("First", bcKeys = true)
        val second = identity("Second", bcKeys = false)
        val one = File(dir, "one.pdf").also { sign(samplePdf("in.pdf"), it, first) }
        val two = File(dir, "two.pdf").also { sign(one, it, second, reason = "second") }
        val bytes = two.readBytes()
        assertArrayEquals("first revision stays a prefix", one.readBytes(), bytes.copyOfRange(0, one.length().toInt()))
        val sigs = signatures(bytes)
        assertEquals(2, sigs.size)
        assertTrue("first signature still verifies", cmsVerifies(sigs[0], first.cert))
        assertTrue("second signature verifies", cmsVerifies(sigs[1], second.cert))
        assertFalse(sigs[0].coversWholeFile(bytes.size))
        assertTrue(sigs[1].coversWholeFile(bytes.size))
    }

    @Test
    fun signedDocument_stillOpensAndKeepsItsPages() {
        val id = identity("A", bcKeys = true)
        val out = File(dir, "out.pdf").also { sign(samplePdf("in.pdf", pages = 3), it, id) }
        PDDocument.load(out).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(1, doc.signatureDictionaries.size)
        }
    }

    @Test
    fun encryptedInput_isRefused_andLeavesNoOutput() {
        val id = identity("A", bcKeys = true)
        val plain = samplePdf("plain.pdf")
        val encrypted = File(dir, "enc.pdf")
        PDDocument.load(plain).use { doc ->
            val policy = StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(encrypted)
        }
        val out = File(dir, "out.pdf")
        try {
            sign(encrypted, out, id)
            fail("encrypted input must be refused")
        } catch (e: Exception) {
            // expected: IllegalArgumentException (or an invalid-password IOException from PDFBox)
        }
        assertFalse(out.exists())
        assertFalse(File(out.path + ".part").exists())
    }

    @Test
    fun missingInput_andEmptyChain_failWithoutOutput() {
        val id = identity("A", bcKeys = true)
        val out = File(dir, "out.pdf")
        try {
            PdfBoxCmsSigner.signIncremental(File(dir, "nope.pdf"), out, id.key, listOf(id.cert))
            fail("missing input must fail")
        } catch (e: IllegalArgumentException) {
        }
        try {
            PdfBoxCmsSigner.signIncremental(samplePdf("in.pdf"), out, id.key, emptyList())
            fail("empty chain must fail")
        } catch (e: IllegalArgumentException) {
        }
        assertFalse(out.exists())
        assertFalse(File(out.path + ".part").exists())
    }

    @Test
    fun signatureTooLargeForReservedSpace_failsAndLeavesNoOutput() {
        val id = identity("A", bcKeys = true)
        val out = File(dir, "out.pdf")
        try {
            PdfBoxCmsSigner.signIncremental(
                samplePdf("in.pdf"), out, id.key, listOf(id.cert), preferredSignatureSize = 64
            )
            fail("a 64-byte reservation cannot hold a CMS signature")
        } catch (e: Exception) {
            // expected: PDFBox reports not enough space for the signature
        }
        assertFalse(out.exists())
        assertFalse(File(out.path + ".part").exists())
    }

    // ------------------------------------------------------------------ visible appearance

    private fun bitmap(): Bitmap = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun widgetOnPage(doc: PDDocument, pageIndex: Int) =
        doc.signatureFields.single().widgets.single().let { w ->
            w to doc.getPage(pageIndex).annotations.any { it.cosObject === w.cosObject }
        }

    @Test
    fun visibleSignature_withImage_hasRectPageAndImageAppearance_andStillVerifies() {
        val id = identity("Vis", bcKeys = true)
        val out = File(dir, "vis.pdf")
        PdfBoxCmsSigner.signIncremental(
            samplePdf("in.pdf", pages = 2), out, id.key, listOf(id.cert),
            visible = PdfBoxCmsSigner.VisibleAppearance(1, 100f, 200f, 150f, 50f, 0, bitmap())
        )
        PDDocument.load(out).use { doc ->
            val (widget, onPage2) = widgetOnPage(doc, 1)
            assertTrue("widget must be on the requested page", onPage2)
            assertFalse("and not on page 1", doc.getPage(0).annotations.any { it.cosObject === widget.cosObject })
            assertEquals(100f, widget.rectangle.lowerLeftX, 0.01f)
            assertEquals(200f, widget.rectangle.lowerLeftY, 0.01f)
            assertEquals(150f, widget.rectangle.width, 0.01f)
            assertEquals(50f, widget.rectangle.height, 0.01f)
            val stream = widget.appearance.normalAppearance.appearanceStream
            assertEquals(150f, stream.bBox.width, 0.01f)
            assertTrue("the bitmap must be a resource of the appearance", stream.resources.xObjectNames.iterator().hasNext())
        }
        assertTrue(cmsVerifies(signatures(out.readBytes()).single(), id.cert))
    }

    @Test
    fun visibleSignature_withoutImage_drawsSignerDateReasonAndLocation() {
        val id = identity("Text Signer", bcKeys = true)
        val out = File(dir, "txt.pdf")
        PdfBoxCmsSigner.signIncremental(
            samplePdf("in.pdf"), out, id.key, listOf(id.cert), reason = "approval", location = "Chennai",
            visible = PdfBoxCmsSigner.VisibleAppearance(0, 72f, 72f, 220f, 60f)
        )
        PDDocument.load(out).use { doc ->
            val text = String(doc.signatureFields.single().widgets.single().appearance.normalAppearance
                .appearanceStream.contentStream.toByteArray(), Charsets.ISO_8859_1)
            assertTrue(text, text.contains("Digitally signed by Text Signer"))
            assertTrue(text.contains("Reason: approval"))
            assertTrue(text.contains("Location: Chennai"))
        }
        assertTrue(cmsVerifies(signatures(out.readBytes()).single(), id.cert))
    }

    @Test
    fun visibleSignature_onRotatedPage_getsARotationMatrix_withSwappedAppearanceBox() {
        val id = identity("Rot", bcKeys = true)
        val rotated = File(dir, "rot_in.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).apply { rotation = 90 }
            doc.addPage(page)
            doc.save(rotated)
        }
        val out = File(dir, "rot.pdf")
        // user-space rect 40 wide x 150 high on a page displayed rotated by 90: the upright form is 150 x 40
        PdfBoxCmsSigner.signIncremental(
            rotated, out, id.key, listOf(id.cert),
            visible = PdfBoxCmsSigner.VisibleAppearance(0, 50f, 100f, 40f, 150f, 90, bitmap())
        )
        PDDocument.load(out).use { doc ->
            val stream = doc.signatureFields.single().widgets.single().appearance.normalAppearance.appearanceStream
            assertEquals(150f, stream.bBox.width, 0.01f)
            assertEquals(40f, stream.bBox.height, 0.01f)
            val matrix = stream.cosObject.getDictionaryObject(COSName.MATRIX) as COSArray
            assertEquals(listOf(0f, 1f, -1f, 0f, 0f, 0f), matrix.toFloatArray().toList())
        }
    }

    @Test
    fun visibleSignature_invalidPageOrEmptyArea_isRefused_withoutOutput() {
        val id = identity("Bad", bcKeys = true)
        val out = File(dir, "bad.pdf")
        for (v in listOf(
            PdfBoxCmsSigner.VisibleAppearance(5, 10f, 10f, 100f, 40f),
            PdfBoxCmsSigner.VisibleAppearance(0, 10f, 10f, 0f, 40f)
        )) {
            try {
                PdfBoxCmsSigner.signIncremental(samplePdf("in.pdf"), out, id.key, listOf(id.cert), visible = v)
                fail("must be refused")
            } catch (e: IllegalArgumentException) {
            }
            assertFalse(out.exists())
            assertFalse(File(out.path + ".part").exists())
        }
    }

    @Test
    fun nonLatinNames_neverBreakTheTextAppearance() {
        assertEquals("????? ok", PdfBoxCmsSigner.winAnsiOnly("\u0ba4\u0bae\u0bbf\u0bb4\u0bcd ok"))
        val id = identity("\u0ba4\u0bae\u0bbf\u0bb4\u0bcd Signer", bcKeys = true)
        val out = File(dir, "tamil.pdf")
        PdfBoxCmsSigner.signIncremental(
            samplePdf("in.pdf"), out, id.key, listOf(id.cert), reason = "\u0ba4\u0bae\u0bbf\u0bb4\u0bcd",
            visible = PdfBoxCmsSigner.VisibleAppearance(0, 72f, 72f, 220f, 60f)
        )
        assertTrue(cmsVerifies(signatures(out.readBytes()).single(), id.cert))
    }

    @Test
    fun fieldNameAndHashAlgorithm_areHonoured() {
        val id = identity("Named", bcKeys = true)
        val out = File(dir, "named.pdf")
        PdfBoxCmsSigner.signIncremental(
            samplePdf("in.pdf"), out, id.key, listOf(id.cert), fieldName = "Signature_12345", hashAlgorithm = "SHA512"
        )
        PDDocument.load(out).use { doc -> assertEquals("Signature_12345", doc.signatureFields.single().fullyQualifiedName) }
        assertTrue(cmsVerifies(signatures(out.readBytes()).single(), id.cert))
        try {
            PdfBoxCmsSigner.signIncremental(samplePdf("in2.pdf"), File(dir, "x.pdf"), id.key, listOf(id.cert), hashAlgorithm = "MD5")
            fail("MD5 must be refused")
        } catch (e: IllegalArgumentException) {
        }
    }

    /**
     * Writes the artefacts the independent validators check (pdfsig, qpdf, pyHanko) into
     * `<external files>/signing-spike/`. Always passes if it can write; the verdicts come from the
     * validators in `signing-validation.yml`, not from this test.
     */
    @Test
    fun exportArtefactsForIndependentValidation() {
        val export = File(context.getExternalFilesDir(null), "signing-spike").apply { deleteRecursively(); mkdirs() }
        val first = identity("Spike Signer One", bcKeys = true)
        val second = identity("Spike Signer Two", bcKeys = false)
        val unsigned = samplePdf("unsigned.pdf", pages = 3).copyTo(File(export, "unsigned.pdf"), overwrite = true)
        val one = File(export, "signed_one.pdf").also { sign(unsigned, it, first) }
        val two = File(export, "signed_two.pdf").also { sign(one, it, second, reason = "second") }
        val tampered = File(export, "signed_tampered.pdf")
        tampered.writeBytes(one.readBytes().also {
            val at = String(it, Charsets.ISO_8859_1).indexOf("%PDF-1.") + 7
            it[at] = if (it[at] == '6'.code.toByte()) '5'.code.toByte() else '6'.code.toByte()
        })
        File(export, "signed_appended.pdf").writeBytes(
            one.readBytes() + "\n%% appended after signing\n".toByteArray(Charsets.ISO_8859_1)
        )
        PdfBoxCmsSigner.signIncremental(
            unsigned, File(export, "signed_visible.pdf"), first.key, listOf(first.cert), reason = "visible", location = "Test lab",
            visible = PdfBoxCmsSigner.VisibleAppearance(0, 72f, 600f, 240f, 70f)
        )
        for ((name, id) in listOf("signer1.pem" to first, "signer2.pem" to second)) {
            val b64 = Base64.encodeToString(id.cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
            File(export, name).writeText("-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n")
        }
        assertTrue(two.length() > one.length())
        assertTrue(File(export, "signed_tampered.pdf").exists())
    }
}
