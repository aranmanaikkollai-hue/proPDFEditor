package com.propdf.security.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.security.data.database.SecurityDatabase
import com.propdf.security.data.repository.SecurityRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 7: metadata removal and sanitising now run on PDFBox (no iText). Fixtures are written with PDFBox and
 * inspected by re-opening the output with PDFBox. NOT RUN in the authoring environment (no SDK/device).
 * Only what is asserted below is claimed.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxSanitizeInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dir: File   // served by TestPdfProvider as content://com.propdfeditor.test.pdfs/<name>

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        dir = File(context.cacheDir, "testpdfs").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun contentUri(f: File): Uri = Uri.parse("content://com.propdfeditor.test.pdfs/${f.name}")

    private fun repository(): Pair<SecurityRepository, SecurityDatabase> {
        val db = Room.inMemoryDatabaseBuilder(context, SecurityDatabase::class.java).allowMainThreadQueries().build()
        return SecurityRepository(context, db.securityOperationDao(), db.redactionDao(), db.secureDocumentDao()) to db
    }

    /** One page of text, filled Info, an XMP stream, JavaScript (OpenAction + Names), a link, a note and a document-level form. */
    private fun busyFixture(name: String): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(40f, 700f); cs.showText("KEEPME"); cs.endText()
            }
            doc.documentInformation.apply {
                title = "SECRET-TITLE"; author = "SECRET-AUTHOR"; subject = "SECRET-SUBJECT"
                keywords = "SECRET-KEYWORDS"; creator = "SECRET-CREATOR"; producer = "SECRET-PRODUCER"
            }
            val catalog = doc.documentCatalog.cosObject
            val xmp = doc.document.createCOSStream()
            xmp.createOutputStream().use { it.write("<x:xmpmeta>SECRET-XMP</x:xmpmeta>".toByteArray()) }
            xmp.setName(COSName.TYPE, "Metadata"); xmp.setName(COSName.SUBTYPE, "XML")
            catalog.setItem(COSName.getPDFName("Metadata"), xmp)

            val js = COSDictionary().apply { setName(COSName.S, "JavaScript"); setString(COSName.getPDFName("JS"), "app.alert('SECRET-JS')") }
            catalog.setItem(COSName.getPDFName("OpenAction"), js)
            val names = COSDictionary().apply { setItem(COSName.getPDFName("JavaScript"), COSDictionary()) }
            catalog.setItem(COSName.getPDFName("Names"), names)
            catalog.setItem(COSName.getPDFName("AcroForm"), COSDictionary())

            val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 50f, 20f) }
            val note = PDAnnotationText().apply { rectangle = PDRectangle(100f, 100f, 20f, 20f); contents = "SECRET-NOTE" }
            page.annotations = listOf<PDAnnotation>(link, note)
            doc.save(file)
        }
        return file
    }

    private fun emptyDest(name: String) = File(dir, name).apply { writeBytes(ByteArray(0)) }

    private fun text(f: File): String = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    private fun rawContains(f: File, needle: String) = String(f.readBytes(), Charsets.ISO_8859_1).contains(needle)

    @Test fun removeMetadata_clearsInfoAndXmp_keepsContent_overContentUris() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = busyFixture("meta-src.pdf"); val dest = emptyDest("meta-dest.pdf")
            val r = repo.removeMetadata(contentUri(src), contentUri(dest))
            assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
            PDDocument.load(dest).use { d ->
                val info = d.documentInformation
                assertNull(info.title); assertNull(info.author); assertNull(info.subject)
                assertNull(info.keywords); assertNull(info.creator)
                assertNull(info.creationDate); assertNull(info.modificationDate)
                assertNull(d.documentCatalog.cosObject.getItem(COSName.getPDFName("Metadata")))
                assertEquals(1, d.numberOfPages)
            }
            assertTrue(text(dest).contains("KEEPME"))
            for (secret in listOf("SECRET-TITLE", "SECRET-AUTHOR", "SECRET-SUBJECT", "SECRET-KEYWORDS", "SECRET-CREATOR", "SECRET-XMP")) {
                assertFalse("$secret must not remain in the file bytes", rawContains(dest, secret))
            }
        } finally { db.close() }
    }

    @Test fun sanitize_removesScriptsNotesFormAndMetadata_keepsLinksAndText() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = busyFixture("san-src.pdf"); val dest = emptyDest("san-dest.pdf")
            val r = repo.sanitizeDocument(contentUri(src), contentUri(dest))
            assertTrue(r.exceptionOrNull()?.toString(), r.isSuccess)
            PDDocument.load(dest).use { d ->
                val cat = d.documentCatalog.cosObject
                for (key in listOf("OpenAction", "Names", "AA", "AcroForm", "Metadata", "OCProperties")) {
                    assertNull("catalog /$key must be gone", cat.getItem(COSName.getPDFName(key)))
                }
                val annots = d.getPage(0).annotations
                assertEquals(1, annots.size)
                assertEquals("Link", annots[0].subtype)
                assertNull(d.documentInformation.title)
            }
            assertTrue(text(dest).contains("KEEPME"))
            for (secret in listOf("SECRET-JS", "SECRET-NOTE", "SECRET-TITLE", "SECRET-XMP")) {
                assertFalse("$secret must not remain in the file bytes", rawContains(dest, secret))
            }
            assertTrue("sanitised flag recorded", db.secureDocumentDao().getDocument(contentUri(dest).toString())?.isSanitized == true)
        } finally { db.close() }
    }

    @Test fun encryptedSource_isRefused_andDestinationStaysEmpty() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val plain = busyFixture("enc-plain.pdf"); val enc = File(dir, "enc.pdf")
            PDDocument.load(plain).use { d ->
                val ap = AccessPermission()
                val policy = StandardProtectionPolicy("owner-pw", "user-pw", ap).apply { encryptionKeyLength = 128 }
                d.protect(policy); d.save(enc)
            }
            val dest = emptyDest("enc-dest.pdf")
            val r1 = repo.removeMetadata(contentUri(enc), contentUri(dest))
            val r2 = repo.sanitizeDocument(contentUri(enc), contentUri(dest))
            assertTrue(r1.isFailure); assertTrue(r2.isFailure)
            assertNotNull(r1.exceptionOrNull()?.message)
            assertEquals("destination untouched", 0L, dest.length())
        } finally { db.close() }
    }

    @Test fun noTempFilesLeftBehind() = runBlocking<Unit> {
        val (repo, db) = repository()
        try {
            val src = busyFixture("tmp-src.pdf"); val dest = emptyDest("tmp-dest.pdf")
            repo.removeMetadata(contentUri(src), contentUri(dest))
            repo.sanitizeDocument(contentUri(src), contentUri(dest))
            val leftovers = context.cacheDir.listFiles { f -> f.name.startsWith("pdf_meta") || f.name.startsWith("pdf_sanitize") || f.name.startsWith("pdf_out") }
            assertTrue(leftovers.isNullOrEmpty())
        } finally { db.close() }
    }
}
