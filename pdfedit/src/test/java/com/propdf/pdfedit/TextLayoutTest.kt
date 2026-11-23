package com.propdf.pdfedit

import com.propdf.pdfedit.convert.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {
    private val chars: (String) -> Double = { it.length.toDouble() }

    @Test
    fun wordsWrapAtTheLimit() {
        assertEquals(
            listOf("the quick", "brown fox", "jumps over", "the lazy", "dog"),
            TextLayout.wrap("the quick brown fox jumps over the lazy dog", 10.0, chars)
        )
    }

    @Test
    fun noLineIsEverWiderThanTheLimit() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau"
        for (limit in listOf(5.0, 8.0, 13.0, 21.0, 40.0)) {
            val lines = TextLayout.wrap(text, limit, chars)
            assertTrue("limit $limit", lines.all { it.length <= limit })
        }
    }

    @Test
    fun wrappingKeepsEveryNonSpaceCharacterInOrder() {
        val text = "alpha beta gamma delta epsilon zeta eta theta supercalifragilisticexpialidocious end"
        val lines = TextLayout.wrap(text, 9.0, chars)
        assertEquals(text.filter { it != ' ' }, lines.joinToString("").filter { it != ' ' })
    }

    @Test
    fun leadingIndentationIsKeptOnTheFirstLine() {
        assertEquals(listOf("   indented", "line that", "wraps around"), TextLayout.wrap("   indented line that wraps around", 12.0, chars))
    }

    @Test
    fun aWordWiderThanALineIsSplitBetweenCharacters() {
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uvwxyz"), TextLayout.wrap("abcdefghijklmnopqrstuvwxyz", 10.0, chars))
    }

    @Test
    fun blankLinesAndParagraphBreaksAreKept() {
        assertEquals(listOf("a", "", "", "b"), TextLayout.wrap("a\n\n\nb", 10.0, chars))
    }

    @Test
    fun spacesAtAWrapPointAreDropped() {
        assertEquals(listOf("word", "word"), TextLayout.wrap("word   word", 4.0, chars))
    }

    @Test
    fun trailingSpacesAreTrimmed() {
        assertEquals(listOf("abc"), TextLayout.wrap("abc    ", 10.0, chars))
    }

    @Test
    fun surrogatePairsAreNotCutInTheMiddle() {
        val pair = "\uD83D\uDE00"
        val lines = TextLayout.wrap(pair.repeat(5), 2.0, { it.codePointCount(0, it.length).toDouble() })
        assertEquals(listOf(pair + pair, pair + pair, pair), lines)
    }

    @Test
    fun narrowerThanOneCharacterStillMakesProgress() {
        assertEquals(listOf("a", "b", "c"), TextLayout.wrap("abc", 0.5, chars))
    }

    @Test
    fun paginationFillsPagesInOrder() {
        val lines = (1..25).map { "line $it" }
        val pages = TextLayout.paginate(lines, 10)
        assertEquals(listOf(10, 10, 5), pages.map { it.size })
        assertEquals(lines, pages.flatten())
    }

    @Test
    fun noLinesMeansNoPagesAndALimitOfZeroStillWorks() {
        assertTrue(TextLayout.paginate(emptyList(), 10).isEmpty())
        assertEquals(3, TextLayout.paginate(listOf("a", "b", "c"), 0).size)
    }
}
