package com.propdf.pdfedit

import com.propdf.pdfedit.content.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSanitizerTest {
    private val latin: (String) -> Boolean = { s -> s.all { it.code in 32..126 } }

    @Test
    fun plainTextPassesThroughUnchanged() {
        val r = TextSanitizer.sanitize("Hello, world 123", latin)
        assertEquals("Hello, world 123", r.text)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun newlinesAreKeptCarriageReturnsDroppedTabsBecomeSpaces() {
        val r = TextSanitizer.sanitize("a\tb\r\nc", latin)
        assertEquals("a b\nc", r.text)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun unsupportedCharactersBecomeQuestionMarksAndAreReportedOnce() {
        val r = TextSanitizer.sanitize("a\u00E9b\u00E9c", latin)
        assertEquals("a?b?c", r.text)
        assertEquals(listOf("\u00E9"), r.dropped)
    }

    @Test
    fun withoutAQuestionMarkGlyphTheCharacterIsRemoved() {
        val noQuestion: (String) -> Boolean = { s -> s != "?" && latin(s) }
        val r = TextSanitizer.sanitize("a\u00E9b", noQuestion)
        assertEquals("ab", r.text)
        assertEquals(listOf("\u00E9"), r.dropped)
    }

    @Test
    fun surrogatePairsAreTreatedAsOneCharacter() {
        val smiley = "\uD83D\uDE00"
        val r = TextSanitizer.sanitize("x" + smiley + "y", latin)
        assertEquals("x?y", r.text)
        assertEquals(listOf(smiley), r.dropped)
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        val r = TextSanitizer.sanitize("", latin)
        assertEquals("", r.text)
        assertTrue(r.dropped.isEmpty())
    }
}
