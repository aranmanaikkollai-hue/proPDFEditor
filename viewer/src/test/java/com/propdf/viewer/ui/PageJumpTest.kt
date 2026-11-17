package com.propdf.viewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageJumpTest {

    @Test
    fun firstAndLastPageMapToZeroBasedIndexes() {
        assertEquals(0, PageJump.parse("1", 10))
        assertEquals(9, PageJump.parse("10", 10))
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(4, PageJump.parse("  5 ", 10))
    }

    @Test
    fun outOfRangeIsRejected() {
        assertNull(PageJump.parse("0", 10))
        assertNull(PageJump.parse("11", 10))
        assertNull(PageJump.parse("2147483648", 10))
    }

    @Test
    fun nonDigitsAreRejected() {
        assertNull(PageJump.parse("", 10))
        assertNull(PageJump.parse("   ", 10))
        assertNull(PageJump.parse("-1", 10))
        assertNull(PageJump.parse("+3", 10))
        assertNull(PageJump.parse("3.0", 10))
        assertNull(PageJump.parse("abc", 10))
        assertNull(PageJump.parse("1 2", 10))
    }

    @Test
    fun nonAsciiDigitsAreRejected() {
        // Arabic-Indic digit three
        assertNull(PageJump.parse("\u0663", 10))
    }

    @Test
    fun emptyDocumentAcceptsNothing() {
        assertNull(PageJump.parse("1", 0))
    }

    @Test
    fun veryLongInputIsRejectedWithoutOverflow() {
        assertNull(PageJump.parse("0000000000001", 10))
        assertNull(PageJump.parse("99999999999999999999", 10))
    }
}
