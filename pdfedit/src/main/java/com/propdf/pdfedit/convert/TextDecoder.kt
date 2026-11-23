package com.propdf.pdfedit.convert

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Turns the bytes of a text file into a string with "\n" line endings. Pure, unit tested on the JVM. */
object TextDecoder {

    class Decoded(val text: String, val guessed: Boolean)

    fun decode(bytes: ByteArray): Decoded {
        var guessed = false
        val text: String = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> {
                val strict = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    strict.decode(ByteBuffer.wrap(bytes)).toString()
                } catch (e: CharacterCodingException) {
                    guessed = true
                    String(bytes, Charset.forName("windows-1252"))
                }
            }
        }
        return Decoded(text.replace("\r\n", "\n").replace('\r', '\n'), guessed)
    }
}
