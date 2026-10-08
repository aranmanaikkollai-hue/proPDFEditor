package com.propdf.security.encryption

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.propdf.security.data.database.SecurityDatabase
import com.propdf.security.data.entity.EncryptionType
import com.propdf.security.data.repository.SecurityRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar

/**
 * Phase 3 suite for password security on PDFBox.
 *
 * Independent fixtures: encrypted inputs for every algorithm are written by iText (a different
 * implementation from the one under test) and PDFBox-written output is reopened with iText, so the
 * tests prove interoperability in both directions rather than only self-consistency. Only the
 * algorithms and revisions exercised below are claimed to work.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxPasswordInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File          // served by TestPdfProvider as content://com.propdfeditor.test.pdfs/<name>
    private lateinit var engine: PdfBoxPasswordEngine

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "testpdfs").apply { deleteRecursively(); mkdirs() }
        engine = PdfBoxPasswordEngine(context)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    // ------------------------------------------------------------------ fixtures

    private fun plain(name: String, pages: Int = 3): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            for (i in 1..pages) {
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(40f, 700f); cs.showText("PAGE$i"); cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    /** Encrypted fixture written by iText (independent of PDFBox). */
    private fun iTextEncrypted(
        name: String, user: ByteArray?, owner: ByteArray, perms: Int, algorithm: Int, pages: Int = 3
    ): File {
        val file = File(dir, name)
        val props = WriterProperties().setStandardEncryption(user, owner, perms, algorithm)
        val doc = com.itextpdf.kernel.pdf.PdfDocument(PdfWriter(file.absolutePath, props))
        try {
            val font = PdfFontFactory.createFont(StandardFonts.HELVETICA)
            for (i in 1..pages) {
                val page = doc.addNewPage()
                PdfCanvas(page).beginText().setFontAndSize(font, 18f).moveText(40.0, 700.0).showText("PAGE$i").endText()
            }
        } finally { doc.close() }
        return file
    }

    private fun labels(file: File, password: String = ""): List<String> =
        PDDocument.load(file, password).use { d ->
            (1..d.numberOfPages).map { n ->
                PDFTextStripper().also { it.startPage = n; it.endPage = n }.getText(d).trim()
            }
        }

    private fun pdfboxRejects(file: File, password: String) {
        try { PDDocument.load(file, password).close(); fail("PDFBox must reject password '<redacted>'") }
        catch (expected: InvalidPasswordException) { }
    }

    private fun iTextOpens(file: File, password: ByteArray): Int {
        val reader = PdfReader(file.absolutePath, ReaderProperties().setPassword(password))
        val doc = com.itextpdf.kernel.pdf.PdfDocument(reader)
        try { assertTrue(reader.isEncrypted); return doc.numberOfPages } finally { doc.close() }
    }

    private fun tmpFiles() = (dir.listFiles()?.toList().orEmpty() + context.cacheDir.listFiles()?.toList().orEmpty())
        .filter { it.name.endsWith(".tmp") && it.name.startsWith(".") }

    private fun out(name: String) = File(dir, name)

    private fun renderAll(file: File, expectedPages: Int) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                assertEquals(expectedPages, r.pageCount)
                for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE); p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bmp.recycle()
                }
            }
        } finally { pfd.close() }
    }

    // ------------------------------------------------------------------ 1. unencrypted -> encrypt (every algorithm)

    @Test fun encrypt_everyAlgorithm_roundTripsAndInteroperatesWithIText() = runBlocking<Unit> {
        val cases = listOf(
            PdfBoxPasswordEngine.Algorithm.RC4_40 to "rc4-40",
            PdfBoxPasswordEngine.Algorithm.RC4_128 to "rc4-128",
            PdfBoxPasswordEngine.Algorithm.AES_128 to "aes-128",
            PdfBoxPasswordEngine.Algorithm.AES_256 to "aes-256"
        )
        for ((algorithm, tag) in cases) {
            val src = plain("src-$tag.pdf"); val before = src.readBytes()
            val dst = out("enc-$tag.pdf")
            engine.encrypt(src, dst, PdfBoxPasswordEngine.EncryptionRequest("userpw", "ownerpw", PdfPermissions.ALL, algorithm))
            assertTrue("$tag: input must not change", before.contentEquals(src.readBytes()))
            assertTrue("$tag: output encrypted", engine.isEncrypted(dst))
            assertEquals("$tag: labels", listOf("PAGE1", "PAGE2", "PAGE3"), labels(dst, "userpw"))
            pdfboxRejects(dst, ""); pdfboxRejects(dst, "wrong")
            assertEquals("$tag: iText reads PDFBox output", 3, iTextOpens(dst, "userpw".toByteArray()))
            val info = engine.inspect(dst, "ownerpw")
            when (algorithm) {
                PdfBoxPasswordEngine.Algorithm.RC4_40 -> assertEquals(40, info.keyLengthBits)
                PdfBoxPasswordEngine.Algorithm.RC4_128 -> assertEquals(128, info.keyLengthBits)
                PdfBoxPasswordEngine.Algorithm.AES_128 -> { assertEquals(128, info.keyLengthBits); assertEquals(4, info.revision) }
                PdfBoxPasswordEngine.Algorithm.AES_256 -> { assertEquals(5, info.version); assertTrue(info.revision in 5..6) }
            }
        }
        assertTrue(tmpFiles().isEmpty())
    }

    // ------------------------------------------------------------------ 2-4. open: correct / wrong / missing password

    @Test fun iTextWrittenFixtures_openWithCorrectPassword_forEveryRevision() {
        val fixtures = mapOf(
            "rc4-40" to EncryptionConstants.STANDARD_ENCRYPTION_40,
            "rc4-128" to EncryptionConstants.STANDARD_ENCRYPTION_128,
            "aes-128" to EncryptionConstants.ENCRYPTION_AES_128,
            "aes-256" to EncryptionConstants.ENCRYPTION_AES_256
        )
        for ((tag, alg) in fixtures) {
            val f = iTextEncrypted("it-$tag.pdf", "u-$tag".toByteArray(), "o-$tag".toByteArray(), PdfPermissions.ALL, alg)
            val asUser = engine.inspect(f, "u-$tag")
            assertTrue(tag, asUser.isEncrypted); assertEquals(tag, 3, asUser.pageCount); assertFalse(tag, asUser.openedWithOwnerRights)
            val asOwner = engine.inspect(f, "o-$tag")
            assertTrue(tag, asOwner.openedWithOwnerRights)
            assertEquals(tag, listOf("PAGE1", "PAGE2", "PAGE3"), labels(f, "u-$tag"))
        }
    }

    @Test fun wrongPassword_isRejected_forInspectAndDecrypt_withoutOutput() = runBlocking<Unit> {
        val f = iTextEncrypted("wrong.pdf", "right".toByteArray(), "right".toByteArray(), PdfPermissions.ALL, EncryptionConstants.ENCRYPTION_AES_256)
        val before = f.readBytes(); val dst = out("wrong-out.pdf")
        try { engine.inspect(f, "nope"); fail() } catch (e: PdfSecurityException.WrongPassword) { }
        try { engine.decrypt(f, dst, "nope"); fail() } catch (e: PdfSecurityException.WrongPassword) { }
        assertFalse(dst.exists()); assertTrue(tmpFiles().isEmpty())
        assertTrue("original untouched after failure", before.contentEquals(f.readBytes()))
        assertFalse(engine.canOpen(f, "nope")); assertTrue(engine.canOpen(f, "right"))
    }

    @Test fun missingPassword_isReportedAsPasswordRequired_notAsWrong() = runBlocking<Unit> {
        val f = iTextEncrypted("missing.pdf", "right".toByteArray(), "right".toByteArray(), PdfPermissions.ALL, EncryptionConstants.ENCRYPTION_AES_128)
        try { engine.inspect(f, null); fail() } catch (e: PdfSecurityException.PasswordRequired) { }
        try { engine.decrypt(f, out("m-out.pdf"), ""); fail() } catch (e: PdfSecurityException.PasswordRequired) { }
        assertFalse(out("m-out.pdf").exists())
        assertTrue("needs a password, so it counts as encrypted", engine.isEncrypted(f))
    }

    // ------------------------------------------------------------------ 5, 8. decrypt with correct password

    @Test fun decrypt_removesProtection_andOutputIsReadableAndRenderable_forEveryRevision() = runBlocking<Unit> {
        val fixtures = mapOf(
            "rc4-40" to EncryptionConstants.STANDARD_ENCRYPTION_40, "rc4-128" to EncryptionConstants.STANDARD_ENCRYPTION_128,
            "aes-128" to EncryptionConstants.ENCRYPTION_AES_128, "aes-256" to EncryptionConstants.ENCRYPTION_AES_256
        )
        for ((tag, alg) in fixtures) {
            val f = iTextEncrypted("dec-$tag.pdf", "pw-$tag".toByteArray(), "pw-$tag".toByteArray(), PdfPermissions.ALL, alg)
            val dst = out("dec-out-$tag.pdf")
            engine.decrypt(f, dst, "pw-$tag")
            assertFalse(tag, engine.isEncrypted(dst))
            assertEquals(tag, listOf("PAGE1", "PAGE2", "PAGE3"), labels(dst))
            renderAll(dst, 3)
        }
        assertTrue(tmpFiles().isEmpty())
    }

    // ------------------------------------------------------------------ 7. encrypted output cannot be opened without credentials

    @Test fun encryptedOutput_isUnreadableWithoutCredentials_evenToAndroidPdfRenderer() = runBlocking<Unit> {
        val dst = out("locked.pdf")
        engine.encrypt(plain("l-src.pdf"), dst, PdfBoxPasswordEngine.EncryptionRequest("secret", "secret", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256))
        pdfboxRejects(dst, "")
        val pfd = ParcelFileDescriptor.open(dst, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).close(); fail("PdfRenderer must not open a password-protected PDF")
        } catch (expected: SecurityException) { } catch (expected: java.io.IOException) { } finally { pfd.close() }
        // The page text must not be present in the clear in the file bytes either.
        assertFalse(dst.readBytes().toString(Charsets.ISO_8859_1).contains("PAGE1"))
    }

    // ------------------------------------------------------------------ owner / user semantics

    @Test fun decrypt_needsOwnerRights_userPasswordAloneCannotRemoveRestrictions() = runBlocking<Unit> {
        val f = iTextEncrypted("ou.pdf", "userpw".toByteArray(), "ownerpw".toByteArray(), PdfPermissions.ALLOW_PRINTING, EncryptionConstants.ENCRYPTION_AES_256)
        val dst = out("ou-out.pdf")
        try { engine.decrypt(f, dst, "userpw"); fail("user password must not remove protection") }
        catch (e: PdfSecurityException.OwnerPasswordRequired) { }
        assertFalse(dst.exists())
        engine.decrypt(f, dst, "ownerpw")
        assertFalse(engine.isEncrypted(dst))
    }

    @Test fun noBypass_emptyPasswordCannotRemoveProtectionFromRestrictedOpenDocument() = runBlocking<Unit> {
        // Opens with the empty user password, but has restrictions and a real owner password.
        val dst = out("open-restricted.pdf")
        engine.encrypt(plain("or-src.pdf"), dst, PdfBoxPasswordEngine.EncryptionRequest(null, "ownerpw", PdfPermissions.ALLOW_PRINTING, PdfBoxPasswordEngine.Algorithm.AES_256))
        try { engine.decrypt(dst, out("or-out.pdf"), ""); fail("empty password must not unlock") }
        catch (e: PdfSecurityException.OwnerPasswordRequired) { }
        assertFalse(out("or-out.pdf").exists())
    }

    @Test fun notEncryptedAndAlreadyEncrypted_areRefusedClearly() = runBlocking<Unit> {
        val p = plain("ne.pdf")
        try { engine.decrypt(p, out("ne-out.pdf"), "x"); fail() } catch (e: PdfSecurityException.NotEncrypted) { }
        val enc = out("ae.pdf")
        engine.encrypt(p, enc, PdfBoxPasswordEngine.EncryptionRequest("u", "o", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256))
        try { engine.encrypt(enc, out("ae-out.pdf"), PdfBoxPasswordEngine.EncryptionRequest("u2", "o2", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256)); fail() }
        catch (e: PdfSecurityException.AlreadyEncrypted) { }
        assertFalse(out("ne-out.pdf").exists()); assertFalse(out("ae-out.pdf").exists())
    }

    @Test fun blankOwnerPasswordGetsARandomOne_soRestrictionsStayEnforced() = runBlocking<Unit> {
        val dst = out("randowner.pdf")
        engine.encrypt(plain("ro-src.pdf"), dst, PdfBoxPasswordEngine.EncryptionRequest("userpw", "", PdfPermissions.ALLOW_PRINTING, PdfBoxPasswordEngine.Algorithm.AES_256))
        // neither the user password nor the empty string unlocks owner rights
        try { engine.decrypt(dst, out("ro-out.pdf"), "userpw"); fail() } catch (e: PdfSecurityException.OwnerPasswordRequired) { }
        pdfboxRejects(dst, "")
    }

    // ------------------------------------------------------------------ permissions

    @Test fun permissionBits_areStoredAsRequested_andAgreeWithIText() = runBlocking<Unit> {
        // The constants must equal the real iText 7 values the old code used.
        assertEquals(EncryptionConstants.ALLOW_PRINTING, PdfPermissions.ALLOW_PRINTING)
        assertEquals(EncryptionConstants.ALLOW_DEGRADED_PRINTING, PdfPermissions.ALLOW_DEGRADED_PRINTING)
        assertEquals(EncryptionConstants.ALLOW_MODIFY_CONTENTS, PdfPermissions.ALLOW_MODIFY_CONTENTS)
        assertEquals(EncryptionConstants.ALLOW_COPY, PdfPermissions.ALLOW_COPY)
        assertEquals(EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS, PdfPermissions.ALLOW_MODIFY_ANNOTATIONS)
        assertEquals(EncryptionConstants.ALLOW_FILL_IN, PdfPermissions.ALLOW_FILL_IN)
        assertEquals(EncryptionConstants.ALLOW_SCREENREADERS, PdfPermissions.ALLOW_SCREENREADERS)
        assertEquals(EncryptionConstants.ALLOW_ASSEMBLY, PdfPermissions.ALLOW_ASSEMBLY)

        val flags = listOf(
            "print" to PdfPermissions.ALLOW_PRINTING, "degraded" to PdfPermissions.ALLOW_DEGRADED_PRINTING,
            "modify" to PdfPermissions.ALLOW_MODIFY_CONTENTS, "copy" to PdfPermissions.ALLOW_COPY,
            "annotate" to PdfPermissions.ALLOW_MODIFY_ANNOTATIONS, "fill" to PdfPermissions.ALLOW_FILL_IN,
            "access" to PdfPermissions.ALLOW_SCREENREADERS, "assemble" to PdfPermissions.ALLOW_ASSEMBLY, "none" to 0
        )
        for ((tag, mask) in flags) {
            val dst = out("perm-$tag.pdf")
            engine.setPermissions(plain("perm-src-$tag.pdf", 1), dst, "owner-$tag", mask)
            val asAnyone = engine.inspect(dst, null)          // no user password was set
            assertEquals(tag, mask, PdfPermissions.allowedBits(asAnyone.permissionBits))
            assertFalse(tag, asAnyone.openedWithOwnerRights)
            assertTrue(tag, engine.inspect(dst, "owner-$tag").openedWithOwnerRights)
            // independent reader sees the same /P value
            val reader = PdfReader(dst.absolutePath, ReaderProperties())
            val d = com.itextpdf.kernel.pdf.PdfDocument(reader)
            try { assertEquals(tag, mask, (reader.permissions.toInt()) and PdfPermissions.ALL) } finally { d.close() }
        }
        try { engine.setPermissions(plain("blank.pdf"), out("blank-out.pdf"), "", PdfPermissions.ALL); fail() }
        catch (e: PdfSecurityException.BlankOwnerPassword) { }
    }

    @Test fun degradedAndFullPrintingAreDistinct() = runBlocking<Unit> {
        val a = out("deg.pdf"); val b = out("full.pdf")
        engine.setPermissions(plain("d1.pdf", 1), a, "o", PdfPermissions.ALLOW_DEGRADED_PRINTING)
        engine.setPermissions(plain("d2.pdf", 1), b, "o", PdfPermissions.ALLOW_PRINTING)
        assertFalse(PdfPermissions.isAllowed(engine.inspect(a, null).permissionBits, PdfPermissions.ALLOW_PRINTING))
        assertTrue(PdfPermissions.isAllowed(engine.inspect(a, null).permissionBits, PdfPermissions.ALLOW_DEGRADED_PRINTING))
        assertTrue(PdfPermissions.isAllowed(engine.inspect(b, null).permissionBits, PdfPermissions.ALLOW_PRINTING))
    }

    // ------------------------------------------------------------------ password encoding

    @Test fun passwordEncoding_latin1AndLongAndSpaces_roundTripOnEveryAlgorithm() = runBlocking<Unit> {
        val passwords = listOf("plain", "p\u00e4ssw\u00f6rd", "with space and  doubles", "x".repeat(120))
        for (alg in PdfBoxPasswordEngine.Algorithm.values()) {
            // RC4-40 keeps only the first 32 characters meaningful in some readers; the round trip below is PDFBox-to-PDFBox.
            for ((i, pw) in passwords.withIndex()) {
                val enc = out("enc-$alg-$i.pdf")
                engine.encrypt(plain("pe-src-$alg-$i.pdf", 1), enc, PdfBoxPasswordEngine.EncryptionRequest(pw, pw, PdfPermissions.ALL, alg))
                assertTrue("$alg #$i opens with its own password", engine.canOpen(enc, pw))
                assertFalse("$alg #$i rejects another password", engine.canOpen(enc, "other-$pw"))
                val dec = out("dec-$alg-$i.pdf")
                engine.decrypt(enc, dec, pw)
                assertEquals(listOf("PAGE1"), labels(dec))
            }
        }
    }

    @Test fun passwordEncoding_nonLatin1_isUtf8OnAes256_andRefusedWhereItWouldBeStoredLossily() = runBlocking<Unit> {
        val cjk = "\u5bc6\u7801\u30d1\u30b9"
        val ok = out("cjk-aes256.pdf")
        engine.encrypt(plain("cjk-src.pdf", 1), ok, PdfBoxPasswordEngine.EncryptionRequest(cjk, cjk, PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256))
        assertTrue(engine.canOpen(ok, cjk))
        assertFalse("lossy '?' substitute must not open it", engine.canOpen(ok, "????"))
        for (alg in listOf(PdfBoxPasswordEngine.Algorithm.AES_128, PdfBoxPasswordEngine.Algorithm.RC4_128, PdfBoxPasswordEngine.Algorithm.RC4_40)) {
            val dst = out("cjk-$alg.pdf")
            try { engine.encrypt(plain("cjk-src-$alg.pdf", 1), dst, PdfBoxPasswordEngine.EncryptionRequest(cjk, "owner", PdfPermissions.ALL, alg)); fail("$alg") }
            catch (e: PdfSecurityException.UnsupportedPassword) { }
            try { engine.encrypt(plain("cjk-src2-$alg.pdf", 1), dst, PdfBoxPasswordEngine.EncryptionRequest("user", cjk, PdfPermissions.ALL, alg)); fail("$alg owner") }
            catch (e: PdfSecurityException.UnsupportedPassword) { }
            assertFalse(dst.exists())
        }
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun filesWrittenByOlderITextCode_withUtf8Passwords_stillOpen() = runBlocking<Unit> {
        // The old code encrypted with password.toByteArray() (UTF-8) for every algorithm.
        val pw = "p\u00e4ssw\u00f6rd"
        for ((tag, alg) in mapOf("aes-128" to EncryptionConstants.ENCRYPTION_AES_128, "rc4-128" to EncryptionConstants.STANDARD_ENCRYPTION_128, "aes-256" to EncryptionConstants.ENCRYPTION_AES_256)) {
            val f = iTextEncrypted("utf8-$tag.pdf", pw.toByteArray(), pw.toByteArray(), PdfPermissions.ALL, alg)
            assertTrue(tag, engine.canOpen(f, pw))
            assertFalse(tag, engine.canOpen(f, "p\u00e4ssw\u00f6rdX"))
            val dst = out("utf8-out-$tag.pdf"); engine.decrypt(f, dst, pw)
            assertEquals(tag, listOf("PAGE1", "PAGE2", "PAGE3"), labels(dst))
        }
    }

    // ------------------------------------------------------------------ open / save / reopen

    @Test fun encryptDecryptEncryptDecrypt_cycleKeepsContent() = runBlocking<Unit> {
        var current = plain("cycle0.pdf")
        for (round in 1..2) {
            val enc = out("cycle-enc-$round.pdf")
            engine.encrypt(current, enc, PdfBoxPasswordEngine.EncryptionRequest("r$round", "r$round", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256))
            val dec = out("cycle-dec-$round.pdf")
            engine.decrypt(enc, dec, "r$round")
            assertEquals(listOf("PAGE1", "PAGE2", "PAGE3"), labels(dec))
            current = dec
        }
        renderAll(current, 3)
    }

    // ------------------------------------------------------------------ failure, signed, malformed, cancellation

    @Test fun signedPdf_isRefused_andOriginalUntouched() = runBlocking<Unit> {
        val base = plain("signed-base.pdf", 1); val signed = out("signed.pdf"); val tmp = out("signed.tmp0")
        PDDocument.load(base).use { doc ->
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE); setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                setName("test-only"); setSignDate(Calendar.getInstance())
            }
            doc.addSignature(sig, SignatureInterface { _: InputStream -> ByteArray(16) { 1 } })
            FileOutputStream(tmp).use { doc.saveIncremental(it) }
        }
        tmp.copyTo(signed, overwrite = true); tmp.delete()
        val before = signed.readBytes()
        try { engine.encrypt(signed, out("signed-out.pdf"), PdfBoxPasswordEngine.EncryptionRequest("a", "b", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256)); fail() }
        catch (e: PdfSecurityException.SignedDocument) { }
        assertFalse(out("signed-out.pdf").exists()); assertTrue(before.contentEquals(signed.readBytes()))
    }

    @Test fun malformedMissingAndEmptyInputs_failCleanly() = runBlocking<Unit> {
        val garbage = File(dir, "garbage.pdf").apply { writeText("not a pdf at all") }
        val empty = File(dir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        for (f in listOf(garbage, empty, File(dir, "nope.pdf"))) {
            val dst = out("bad-out-${f.name}")
            try { engine.encrypt(f, dst, PdfBoxPasswordEngine.EncryptionRequest("a", "b", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256)); fail(f.name) } catch (e: Exception) { }
            try { engine.decrypt(f, dst, "a"); fail(f.name) } catch (e: Exception) { }
            assertFalse(dst.exists())
            assertFalse("not a protected PDF", engine.isEncrypted(f))
        }
        assertTrue(tmpFiles().isEmpty())
    }

    @Test fun cancellation_publishesNothingAndLeavesNoTempFiles() {
        val src = plain("big.pdf", 150); val dst = out("cancel-out.pdf")
        val job = GlobalScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            engine.encrypt(src, dst, PdfBoxPasswordEngine.EncryptionRequest("a", "b", PdfPermissions.ALL, PdfBoxPasswordEngine.Algorithm.AES_256))
        }
        runBlocking { job.cancelAndJoin() }
        if (job.isCancelled) assertFalse("cancelled work must not publish output", dst.exists())
        assertTrue(tmpFiles().isEmpty())
    }

    // ------------------------------------------------------------------ SAF through the repository (content:// and file://)

    private fun repository(): Pair<SecurityRepository, SecurityDatabase> {
        val db = Room.inMemoryDatabaseBuilder(context, SecurityDatabase::class.java).allowMainThreadQueries().build()
        return SecurityRepository(context, db.securityOperationDao(), db.redactionDao(), db.secureDocumentDao()) to db
    }

    private fun contentUri(f: File): Uri = Uri.parse("content://com.propdfeditor.test.pdfs/${f.name}")

    @Test fun repository_encryptThenDecrypt_overContentUris() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = plain("saf-src.pdf"); val enc = File(dir, "saf-enc.pdf").apply { writeBytes(ByteArray(0)) }
            val r1 = repo.applyPasswordProtection(contentUri(src), "pw", "pw", PdfPermissions.ALL, EncryptionType.AES_256, contentUri(enc))
            assertTrue(r1.isSuccess)
            assertTrue(engine.isEncrypted(enc)); assertEquals(listOf("PAGE1", "PAGE2", "PAGE3"), labels(enc, "pw"))

            val dec = File(dir, "saf-dec.pdf").apply { writeBytes(ByteArray(0)) }
            assertTrue(repo.decryptPdf(contentUri(enc), "pw", contentUri(dec)).isSuccess)
            assertFalse(engine.isEncrypted(dec)); assertEquals(listOf("PAGE1", "PAGE2", "PAGE3"), labels(dec))

            val info = repo.getDocumentInfo(contentUri(enc), "pw").getOrThrow()
            assertTrue(info.isEncrypted); assertEquals(3, info.numberOfPages)
            assertEquals(-1, repo.getDocumentInfo(contentUri(src)).getOrThrow().permissions)
            assertTrue(repo.getDocumentInfo(contentUri(enc)).exceptionOrNull() is PdfSecurityException.PasswordRequired)
        } finally { db.close() }
        assertTrue("staging files removed", context.cacheDir.listFiles { f -> f.name.startsWith("pdf_") && f.name.endsWith(".pdf") }.isNullOrEmpty())
    }

    @Test fun repository_failureLeavesDestinationUntouchedAndSourceIntact() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val enc = iTextEncrypted("saf-locked.pdf", "pw".toByteArray(), "pw".toByteArray(), PdfPermissions.ALL, EncryptionConstants.ENCRYPTION_AES_256)
            val before = enc.readBytes()
            val dest = File(dir, "saf-dest.pdf").apply { writeBytes(ByteArray(0)) }
            val r = repo.decryptPdf(contentUri(enc), "WRONG", contentUri(dest))
            assertTrue(r.exceptionOrNull() is PdfSecurityException.WrongPassword)
            assertEquals("destination must stay empty", 0L, dest.length())
            assertTrue(before.contentEquals(enc.readBytes()))
            // missing source
            val missing = repo.decryptPdf(Uri.parse("content://com.propdfeditor.test.pdfs/nope.pdf"), "pw", contentUri(dest))
            assertTrue(missing.isFailure); assertEquals(0L, dest.length())
        } finally { db.close() }
        assertTrue(context.cacheDir.listFiles { f -> f.name.startsWith("pdf_") && f.name.endsWith(".pdf") }.isNullOrEmpty())
    }

    @Test fun repository_fileScheme_works_andBlankPasswordStillEncryptsAsBefore() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = plain("fs-src.pdf"); val dst = File(dir, "fs-out.pdf").apply { writeBytes(ByteArray(0)) }
            // The Hub passes null/null when the dialog password is blank; behaviour kept: encrypted, opens with empty password.
            assertTrue(repo.applyPasswordProtection(Uri.fromFile(src), null, null, PdfPermissions.ALL, EncryptionType.STANDARD_128, Uri.fromFile(dst)).isSuccess)
            assertTrue(engine.isEncrypted(dst)); assertNotNull(engine.inspect(dst, null))
        } finally { db.close() }
    }
}
