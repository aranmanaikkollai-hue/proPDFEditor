package com.propdf.pdfedit

import com.propdf.pdfedit.convert.UniqueName
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UniqueNameTest {

    @Test
    fun aFreeNameIsKept() = assertEquals("a.png", UniqueName.pick("a.png") { false })

    @Test
    fun aTakenNameGetsACounterBeforeTheExtension() {
        val taken = setOf("a.png")
        assertEquals("a (1).png", UniqueName.pick("a.png") { it in taken })
    }

    @Test
    fun theNextFreeCounterIsUsed() {
        val taken = setOf("a.png", "a (1).png", "a (2).png")
        assertEquals("a (3).png", UniqueName.pick("a.png") { it in taken })
    }

    @Test
    fun aGapInTheCountersIsFilled() {
        val taken = setOf("a.png", "a (2).png")
        assertEquals("a (1).png", UniqueName.pick("a.png") { it in taken })
    }

    @Test
    fun onlyTheLastDotStartsTheExtension() {
        assertEquals("report.v2 (1).pdf", UniqueName.pick("report.v2.pdf") { it == "report.v2.pdf" })
    }

    @Test
    fun namesWithoutAnExtensionOrWithALeadingDot() {
        assertEquals("notes (1)", UniqueName.pick("notes") { it == "notes" })
        assertEquals(".hidden (1)", UniqueName.pick(".hidden") { it == ".hidden" })
    }

    @Test
    fun givesUpAfterTenThousandNames() {
        try {
            UniqueName.pick("a.png") { true }
            fail("expected an exception")
        } catch (e: IOException) {
            // expected
        }
    }
}
