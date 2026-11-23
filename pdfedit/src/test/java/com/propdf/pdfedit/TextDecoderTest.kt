package com.propdf.pdfedit

import com.propdf.pdfedit.convert.TextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecoderTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun plainUtf8IsNotGuessed() {
        val d = TextDecoder.decode("caf\u00E9 \u4E2D".toByteArray(Charsets.UTF_8))
        assertEquals("caf\u00E9 \u4E2D", d.text)
        assertFalse(d.guessed)
    }

    @Test
    fun utf8ByteOrderMarkIsRemoved() {
        val d = TextDecoder.decode(bytes(0xEF, 0xBB, 0xBF) + "hi".toByteArray())
        assertEquals("hi", d.text)
        assertFalse(d.guessed)
    }

    @Test
    fun utf16WithByteOrderMarkIsDecoded() {
        val le = bytes(0xFF, 0xFE) + "h\u00E9".toByteArray(Charsets.UTF_16LE)
        val be = bytes(0xFE, 0xFF) + "h\u00E9".toByteArray(Charsets.UTF_16BE)
        assertEquals("h\u00E9", TextDecoder.decode(le).text)
        assertEquals("h\u00E9", TextDecoder.decode(be).text)
        assertFalse(TextDecoder.decode(le).guessed)
    }

    @Test
    fun invalidUtf8FallsBackToWindows1252AndSaysSo() {
        val d = TextDecoder.decode(bytes(0x63, 0x61, 0x66, 0xE9, 0x20, 0x80))
        assertEquals("caf\u00E9 \u20AC", d.text)
        assertTrue(d.guessed)
    }

    @Test
    fun lineEndingsBecomeNewlines() {
        assertEquals("a\nb\nc\nd", TextDecoder.decode("a\r\nb\rc\nd".toByteArray()).text)
    }

    @Test
    fun emptyInputIsEmptyText() {
        val d = TextDecoder.decode(ByteArray(0))
        assertEquals("", d.text)
        assertFalse(d.guessed)
    }
}
