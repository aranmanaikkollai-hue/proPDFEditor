package com.propdfeditor.batch.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.editor.ui.PageDuplicator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Regression: Page Editor duplicate used importPage()+addPage(), inserting each copy twice. */
@RunWith(AndroidJUnit4::class)
class PageDuplicatorInstrumentedTest {

    @Before
    fun init() {
        PDFBoxResourceLoader.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun threePageDoc(): PDDocument {
        val doc = PDDocument()
        for (label in listOf("P1", "P2", "P3")) {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(60f, 700f); cs.showText(label); cs.endText()
            }
        }
        return doc
    }

    private fun pageTexts(doc: PDDocument): List<String> = (1..doc.numberOfPages).map { n ->
        PDFTextStripper().also { it.startPage = n; it.endPage = n }.getText(doc).trim()
    }

    @Test
    fun duplicatingOnePage_addsExactlyOneCopy() {
        threePageDoc().use { doc ->
            assertEquals(1, PageDuplicator.appendCopies(doc, listOf(1)))
            assertEquals("3 + 1 copy, not 3 + 2", 4, doc.numberOfPages)
            assertEquals(listOf("P1", "P2", "P3", "P2"), pageTexts(doc))
        }
    }

    @Test
    fun duplicatingAllThreePages_yieldsSixPagesInOrder() {
        threePageDoc().use { doc ->
            assertEquals(3, PageDuplicator.appendCopies(doc, listOf(2, 0, 1)))
            assertEquals("exactly 6, not 9", 6, doc.numberOfPages)
            assertEquals(listOf("P1", "P2", "P3", "P1", "P2", "P3"), pageTexts(doc))
        }
    }

    @Test
    fun savedAndReopened_pageCountSurvives_andOutOfRangeIndexesAreIgnored() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dup.pdf")
        threePageDoc().use { doc ->
            assertEquals(1, PageDuplicator.appendCopies(doc, listOf(-1, 0, 99)))
            doc.save(file)
        }
        PDDocument.load(file).use { assertEquals(4, it.numberOfPages) }
        assertTrue(file.delete())
    }
}
