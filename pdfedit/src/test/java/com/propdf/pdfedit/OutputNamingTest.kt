package com.propdf.pdfedit

import com.propdf.pdfedit.convert.OutputNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNamingTest {

    @Test
    fun theExtensionAndFolderAreRemoved() {
        assertEquals("Report", OutputNaming.baseName("Report.pdf"))
        assertEquals("c", OutputNaming.baseName("a/b/c.PDF"))
        assertEquals("x", OutputNaming.baseName("..\\x.pdf"))
    }

    @Test
    fun pathTraversalCannotSurvive() {
        val n = OutputNaming.baseName("../../etc/passwd.pdf")
        assertEquals("passwd", n)
        assertFalse(OutputNaming.baseName("a/../b").contains("/"))
    }

    @Test
    fun unsafeCharactersBecomeUnderscores() {
        assertEquals("my file _1", OutputNaming.baseName("my file (1).pdf"))
        assertEquals("a_b", OutputNaming.baseName("a\u0000b.pdf"))
        assertEquals("a_b", OutputNaming.baseName("a:b.pdf"))
    }

    @Test
    fun lettersFromOtherScriptsAreKept() {
        assertEquals("Resume\u0301 2026", OutputNaming.baseName("Resume\u0301 2026.pdf"))
        val tamil = "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD"
        assertEquals(tamil, OutputNaming.baseName(tamil + ".pdf"))
        assertEquals("\u4E2D\u6587", OutputNaming.baseName("\u4E2D\u6587.pdf"))
    }

    @Test
    fun emptyOrDotOnlyNamesBecomeDocument() {
        assertEquals("document", OutputNaming.baseName(""))
        assertEquals("document", OutputNaming.baseName(".pdf"))
        assertEquals("document", OutputNaming.baseName("..."))
        assertEquals("document", OutputNaming.baseName("///"))
    }

    @Test
    fun longNamesAreCutToEightyCharacters() {
        val n = OutputNaming.baseName("a".repeat(200) + ".pdf")
        assertEquals(80, n.length)
    }

    @Test
    fun pageNumbersArePaddedToThreeOrMoreDigits() {
        assertEquals("doc_page_007.png", OutputNaming.pageImageName("doc", 7, 12, "png"))
        assertEquals("doc_page_300.jpg", OutputNaming.pageImageName("doc", 300, 300, "jpg"))
        assertEquals("doc_page_0007.png", OutputNaming.pageImageName("doc", 7, 1500, "png"))
    }

    @Test
    fun namesSortInPageOrder() {
        val names = (1..120).map { OutputNaming.pageImageName("d", it, 120, "png") }
        assertEquals(names, names.sorted())
        assertTrue(names.first().endsWith("001.png"))
    }

    @Test
    fun textFileName() = assertEquals("x.txt", OutputNaming.textName("x"))
}
