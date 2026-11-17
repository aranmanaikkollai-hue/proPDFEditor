package com.propdf.viewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderColorModeTest {

    private val white = -1 // 0xFFFFFFFF
    private val black = 0xFF000000.toInt()

    @Test
    fun normalLeavesColoursUnchanged() {
        assertNull(ReaderColorMode.NORMAL.matrix)
        assertEquals(white, ReaderColorMode.NORMAL.transform(white))
        assertEquals(0xFF336699.toInt(), ReaderColorMode.NORMAL.transform(0xFF336699.toInt()))
        assertEquals(white, ReaderColorMode.NORMAL.pageBackgroundArgb)
    }

    @Test
    fun nightInvertsWhiteAndBlack() {
        assertEquals(black, ReaderColorMode.NIGHT.transform(white))
        assertEquals(white, ReaderColorMode.NIGHT.transform(black))
        assertEquals(black, ReaderColorMode.NIGHT.pageBackgroundArgb)
        assertEquals(0xFF222222.toInt(), ReaderColorMode.NIGHT.canvasBackgroundArgb)
    }

    @Test
    fun nightKeepsAlpha() {
        assertEquals(0x80000000.toInt(), ReaderColorMode.NIGHT.transform(0x80FFFFFF.toInt()))
    }

    @Test
    fun sepiaTurnsWhiteIntoCreamAndKeepsBlack() {
        assertEquals(0xFFFFFFEF.toInt(), ReaderColorMode.SEPIA.transform(white))
        assertEquals(black, ReaderColorMode.SEPIA.transform(black))
    }

    @Test
    fun matricesHaveTwentyEntries() {
        for (mode in ReaderColorMode.values()) {
            val m = mode.matrix
            if (m != null) assertEquals(20, m.size)
        }
    }

    @Test
    fun unknownOrMissingNameFallsBackToNormal() {
        assertEquals(ReaderColorMode.NORMAL, ReaderColorMode.fromName(null))
        assertEquals(ReaderColorMode.NORMAL, ReaderColorMode.fromName("PURPLE"))
        assertEquals(ReaderColorMode.NIGHT, ReaderColorMode.fromName("NIGHT"))
        assertEquals(ReaderColorMode.SEPIA, ReaderColorMode.fromName("SEPIA"))
    }
}
