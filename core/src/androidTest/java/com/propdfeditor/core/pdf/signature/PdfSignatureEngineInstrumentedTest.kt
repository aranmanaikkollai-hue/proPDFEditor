package com.propdfeditor.core.pdf.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
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
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

/**
 * Signature tests. Android instrumented; NOT RUN in the authoring environment (no Gradle/SDK/device there).
 *
 * Secrets: every key pair and keystore password is generated at run time, held in memory / the app cache for the
 * length of a test, and deleted afterwards. No private key, keystore or password is checked in. The only fixture
 * that carries key-related data is certificate.pem, which is a PUBLIC certificate.
 *
 * Fixture provenance: the *.pdf fixtures in assets/signature_fixtures were produced WITHOUT iText or PDFBox
 * (tools/signature_fixtures) and independently validated with poppler pdfsig and openssl cms.
 */
@RunWith(AndroidJUnit4::class)
class PdfSignatureEngineInstrumentedTest {

    private lateinit var context: Context
    private lateinit var testAssets: Context
    private lateinit var dir: File
    private lateinit var engine: PdfSignatureEngine

    private lateinit var storePassword: String
    private lateinit var p12: File
    private lateinit var signerCert: X509Certificate

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        testAssets = instrumentation.context
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "sigtest_" + UUID.randomUUID()).apply { mkdirs() }
        engine = PdfSignatureEngine(context)
        storePassword = UUID.randomUUID().toString()
        val made = makeKeystore("1", "ProPDF Runtime Test Signer", validForDays = 30, password = storePassword, name = "ok.p12")
        p12 = made.first
        signerCert = made.second
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ helpers

    private fun asset(name: String): ByteArray =
        testAssets.assets.open("signature_fixtures/$name").use { it.readBytes() }

    private fun fixture(name: String, as_: String = name): File =
        File(dir, as_).also { it.writeBytes(asset(name)) }

    private fun uri(f: File): Uri = Uri.fromFile(f)

    private fun out(name: String) = File(dir, name)

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** Throwaway RSA-2048 key + self-signed certificate in a PKCS#12 file. Returns the file and the certificate. */
    private fun makeKeystore(
        alias: String, commonName: String, validForDays: Int, password: String, name: String
    ): Pair<File, X509Certificate> {
        val bc = BouncyCastleProvider()
        val gen = KeyPairGenerator.getInstance("RSA", bc).apply { initialize(2048) }
        val pair = gen.generateKeyPair()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val notBefore = Date(now - 2 * day)
        val notAfter = Date(now + validForDays * day)
        val subject = X500Name("CN=$commonName,O=ProPDF Runtime Test")
        val holder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now), notBefore, notAfter, subject, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(pair.private))
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(holder)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, pair.private, password.toCharArray(), arrayOf(cert))
        val file = File(dir, name)
        FileOutputStream(file).use { ks.store(it, password.toCharArray()) }
        return Pair(file, cert)
    }

    private fun box(): Bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }

    private fun sign(
        input: File, output: File, keystore: File = p12, password: String = storePassword, alias: String = "Display Label",
        page: Int = 1, rect: RectF = RectF(72f, 72f, 272f, 142f), bitmap: Bitmap? = null
    ): Result<PdfSignatureEngine.SignatureResult> = runBlocking {
        engine.applyDigitalSignature(
            inputUri = uri(input), outputFile = output, signatureBitmap = bitmap, pageNumber = page, rect = rect,
            keystorePath = keystore.absolutePath, keystorePassword = password, alias = alias, keyPassword = password
        )
    }

    private class ByteRangeInfo(val a: Int, val b: Int, val c: Int, val d: Int, val signedBytes: ByteArray, val cms: ByteArray)

    /** Parses the LAST signature's ByteRange and Contents straight from the file bytes (no PDF library). */
    private fun lastSignature(bytes: ByteArray): ByteRangeInfo {
        val text = String(bytes, Charsets.ISO_8859_1)
        val m = Regex("/ByteRange\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*\\]").findAll(text).last()
        val (a, b, c, d) = m.destructured
        val ai = a.toInt(); val bi = b.toInt(); val ci = c.toInt(); val di = d.toInt()
        val gap = String(bytes, bi, ci - bi, Charsets.ISO_8859_1)
        assertTrue("Contents must be a hex string", gap.startsWith("<") && gap.endsWith(">"))
        val hex = gap.substring(1, gap.length - 1)
        val der = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        val signed = bytes.copyOfRange(ai, ai + bi) + bytes.copyOfRange(ci, ci + di)
        return ByteRangeInfo(ai, bi, ci, di, signed, der)
    }

    /** Independent of iText: BouncyCastle CMS verification of the detached signature over the ByteRange bytes. */
    private fun cmsVerifies(info: ByteRangeInfo, cert: X509Certificate): Boolean {
        val obj = ASN1InputStream(info.cms).use { it.readObject() }
        val cms = CMSSignedData(CMSProcessableByteArray(info.signedBytes), ContentInfo.getInstance(obj))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider()).build(cert)
        val signers = cms.signerInfos.signers as Collection<*>
        return signers.isNotEmpty() && signers.all { (it as SignerInformation).verify(verifier) }
    }

    // ================================================================== cryptographic signature

    @Test
    fun validSignature_hasByteRange_cms_certificate_and_verifies_independently() {
        val input = fixture("unsigned.pdf")
        val output = out("signed.pdf")
        val result = sign(input, output).getOrThrow()

        assertTrue(result.success)
        assertTrue("post-write self-check must have passed", result.integrityVerified)
        assertTrue(output.exists() && output.length() > input.length())
        assertFalse("no partial file may remain", File(dir, "signed.pdf.part").exists())

        val bytes = output.readBytes()
        val info = lastSignature(bytes)
        assertEquals("ByteRange starts at 0", 0, info.a)
        assertEquals("ByteRange ends at end of file", bytes.size, info.c + info.d)
        assertTrue("CMS present", info.cms.size > 100 && info.cms[0] == 0x30.toByte())
        assertTrue("CMS verifies (BouncyCastle, independent of iText)", cmsVerifies(info, signerCert))

        // certificate information is the signer's
        assertEquals(signerCert.subjectX500Principal.name, result.certificateSubject)
        val verified = runBlocking { engine.verifySignatures(uri(output)) }
        assertEquals(1, verified.size)
        val v = verified[0]
        assertTrue(v.isValid)
        assertTrue(v.coversWholeDocument)
        assertEquals(signerCert.subjectX500Principal.name, v.signerName)
        assertEquals(signerCert.issuerX500Principal.name, v.issuerName)
        assertEquals(true, v.signerCertificateValidAtSigningTime)
        assertFalse("trust is never claimed", v.signerTrustVerified)
        assertFalse(v.isTimestamped)
    }

    @Test
    fun signedRevision_keepsOriginalBytesAsPrefix_incrementalSave() {
        val input = fixture("unsigned.pdf")
        val output = out("signed.pdf")
        sign(input, output).getOrThrow()
        val original = input.readBytes()
        val signed = output.readBytes()
        assertTrue(signed.size > original.size)
        assertTrue("original bytes must be an untouched prefix", signed.copyOfRange(0, original.size).contentEquals(original))
    }

    @Test
    fun tampering_afterSigning_invalidatesTheSignature() {
        val input = fixture("unsigned.pdf")
        val output = out("signed.pdf")
        sign(input, output).getOrThrow()
        val bytes = output.readBytes()
        // change one byte of the header version digit (inside the signed range, file still parses)
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("%PDF-1.") + 7
        bytes[at] = if (bytes[at] == '6'.code.toByte()) '5'.code.toByte() else '6'.code.toByte()
        val tampered = out("tampered.pdf").also { it.writeBytes(bytes) }

        assertFalse("BouncyCastle must reject the tampered file", cmsVerifies(lastSignature(bytes), signerCert))
        val v = runBlocking { engine.verifySignatures(uri(tampered)) }
        assertEquals(1, v.size)
        assertFalse("engine must reject the tampered file", v[0].isValid)
        assertNotNull(v[0].failureReason)
    }

    @Test
    fun secondSignature_keepsTheFirstSignatureValid() {
        val first = out("first.pdf")
        sign(fixture("unsigned.pdf"), first).getOrThrow()
        val second = out("second.pdf")
        sign(first, second, rect = RectF(72f, 200f, 272f, 270f)).getOrThrow()
        val results = runBlocking { engine.verifySignatures(uri(second)) }
        assertEquals(2, results.size)
        assertTrue("both signatures valid", results.all { it.isValid })
        assertEquals("only the newest covers the whole document", 1, results.count { it.coversWholeDocument })
        assertTrue(second.readBytes().copyOfRange(0, first.length().toInt()).contentEquals(first.readBytes()))
    }

    @Test
    fun keystoreAliasThatDiffersFromTheLabel_stillSigns() {
        // keystore alias is "1"; the app passes the user's display label
        sign(fixture("unsigned.pdf"), out("a.pdf"), alias = "My Work Certificate").getOrThrow()
        assertTrue(out("a.pdf").exists())
    }

    @Test
    fun independentlyGeneratedFixtures_areClassifiedCorrectly() {
        val valid = runBlocking { engine.verifySignatures(uri(fixture("valid_signed.pdf"))) }
        assertEquals(1, valid.size)
        assertTrue(valid[0].isValid)
        assertTrue(valid[0].coversWholeDocument)
        assertTrue(valid[0].signerName!!.contains("ProPDF Test Signer"))

        val tampered = runBlocking { engine.verifySignatures(uri(fixture("tampered_signed.pdf"))) }
        assertEquals(1, tampered.size)
        assertFalse(tampered[0].isValid)

        val appended = runBlocking { engine.verifySignatures(uri(fixture("appended_after_sign.pdf"))) }
        assertEquals(1, appended.size)
        assertTrue("signature itself still verifies", appended[0].isValid)
        assertFalse("but it no longer covers the whole file", appended[0].coversWholeDocument)
    }

    @Test
    fun unreadableFile_isAnError_notAnEmptyList() {
        val junk = out("junk.pdf").also { it.writeBytes(ByteArray(2000) { 7 }) }
        try {
            runBlocking { engine.verifySignatures(uri(junk)) }
            fail("expected an exception")
        } catch (e: SignatureEngineException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun wrongPassword_failsCleanly_withoutLeakingSecrets() {
        val wrong = UUID.randomUUID().toString()
        val output = out("never.pdf")
        val r = sign(fixture("unsigned.pdf"), output, password = wrong)
        val e = r.exceptionOrNull()
        assertTrue(e is SignatureEngineException.KeystoreProblem)
        val text = e.toString() + (e?.message ?: "") + (e?.cause?.message ?: "")
        assertFalse(text.contains(wrong))
        assertFalse(text.contains(storePassword))
        assertFalse(output.exists())
        assertFalse(File(dir, "never.pdf.part").exists())
    }

    @Test
    fun expiredCertificate_isRejected() {
        val (file, _) = makeKeystore("1", "Expired Signer", validForDays = -1, password = storePassword, name = "expired.p12")
        val output = out("never.pdf")
        val e = sign(fixture("unsigned.pdf"), output, keystore = file).exceptionOrNull()
        assertTrue(e is SignatureEngineException.CertificateProblem)
        assertFalse(output.exists())
    }

    @Test
    fun invalidPageAndPlacement_areRejected_withoutOutput() {
        val input = fixture("unsigned.pdf")
        assertTrue(sign(input, out("p.pdf"), page = 9).exceptionOrNull() is SignatureEngineException.InvalidPage)
        assertTrue(
            sign(input, out("q.pdf"), rect = RectF(5000f, 5000f, 5100f, 5100f)).exceptionOrNull()
                is SignatureEngineException.InvalidPlacement
        )
        assertFalse(out("p.pdf").exists())
        assertFalse(out("q.pdf").exists())
    }

    @Test
    fun timestampRequest_isRejected_notIgnored() {
        val r = runBlocking {
            engine.applyDigitalSignature(
                inputUri = uri(fixture("unsigned.pdf")), outputFile = out("t.pdf"), signatureBitmap = null,
                pageNumber = 1, rect = RectF(72f, 72f, 272f, 142f), keystorePath = p12.absolutePath,
                keystorePassword = storePassword, alias = "1", keyPassword = storePassword,
                timestampUrl = "https://tsa.invalid/"
            )
        }
        assertTrue(r.exceptionOrNull() is SignatureEngineException.Unsupported)
        assertFalse(out("t.pdf").exists())
    }

    // ================================================================== visual signature (NOT cryptographic)

    @Test
    fun visualSignature_isNotCryptographic() {
        val input = fixture("unsigned.pdf")
        val output = out("visual.pdf")
        runBlocking {
            engine.applyVisualSignature(uri(input), output, box(), 1, RectF(72f, 72f, 272f, 142f)).getOrThrow()
        }
        assertTrue(output.length() > input.length())
        assertFalse("no ByteRange", String(output.readBytes(), Charsets.ISO_8859_1).contains("/ByteRange"))
        PDDocument.load(output).use { d ->
            assertTrue("no signature dictionary", d.signatureDictionaries.isEmpty())
            assertEquals(1, d.numberOfPages)
        }
        assertTrue("verifier reports no signatures", runBlocking { engine.verifySignatures(uri(output)) }.isEmpty())
        assertFalse(File(dir, "visual.pdf.part").exists())
    }

    @Test
    fun visualSignatureFixture_hasNoCryptographicSignature() {
        val f = fixture("visual_signature.pdf")
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("/ByteRange"))
        assertTrue(runBlocking { engine.verifySignatures(uri(f)) }.isEmpty())
    }

    @Test
    fun visualSignature_refusesSignedDocument_andLeavesInputUntouched() {
        val signed = fixture("valid_signed.pdf")
        val before = sha256(signed)
        val output = out("never.pdf")
        val e = runBlocking {
            engine.applyVisualSignature(uri(signed), output, box(), 1, RectF(72f, 72f, 272f, 142f))
        }.exceptionOrNull()
        assertTrue(e is SignatureEngineException.SignedDocument)
        assertFalse(output.exists())
        assertEquals(before, sha256(signed))
    }

    @Test
    fun visualSignature_landsInTheRequestedArea_onRotatedPages() {
        for (turn in intArrayOf(0, 90, 180, 270)) {
            val src = File(dir, "rot$turn.pdf")
            PDDocument().use { d ->
                val page = PDPage(PDRectangle(400f, 300f))
                page.rotation = turn
                d.addPage(page)
                d.save(src)
            }
            val dispW = if (turn == 90 || turn == 270) 300f else 400f
            val dispH = if (turn == 90 || turn == 270) 400f else 300f
            val rect = RectF(dispW * 0.10f, dispH * 0.60f, dispW * 0.40f, dispH * 0.90f)
            val output = out("rot${turn}_out.pdf")
            runBlocking { engine.applyVisualSignature(uri(src), output, box(), 1, rect).getOrThrow() }

            PDDocument.load(output).use { d ->
                val bmp = PDFRenderer(d).renderImage(0, 1.0f)
                assertEquals(dispW.toInt(), bmp.width)
                assertEquals(dispH.toInt(), bmp.height)
                val cx = ((rect.left + rect.right) / 2f).toInt()
                val cy = ((rect.top + rect.bottom) / 2f).toInt()
                assertTrue("rotation $turn: stamp centre is dark", Color.red(bmp.getPixel(cx, cy)) < 60)
                val fx = (dispW * 0.9f).toInt()
                val fy = (dispH * 0.1f).toInt()
                assertTrue("rotation $turn: far corner untouched", Color.red(bmp.getPixel(fx, fy)) > 200)
            }
        }
    }

    @Test
    fun visualSignature_invalidPage_isRejected() {
        val e = runBlocking {
            engine.applyVisualSignature(uri(fixture("unsigned.pdf")), out("x.pdf"), box(), 5, RectF(72f, 72f, 272f, 142f))
        }.exceptionOrNull()
        assertTrue(e is SignatureEngineException.InvalidPage)
        assertNull(out("x.pdf").takeIf { it.exists() })
    }
}
