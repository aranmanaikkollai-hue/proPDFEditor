package com.propdf.pdfedit.convert

/** Word wrapping and page breaking for text to PDF. Pure code (the caller supplies the width measure), unit tested on the JVM. */
object TextLayout {

    /**
     * Wraps [text] into lines no wider than [maxWidth] by [widthOf]. Line breaks in the text are kept and empty lines stay
     * empty. Leading spaces of a paragraph are kept, spaces at a wrap point are dropped, and a word wider than a line is
     * split between characters. Line endings must already be "\n".
     */
    fun wrap(text: String, maxWidth: Double, widthOf: (String) -> Double): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }
            wrapParagraph(paragraph.trimEnd(), maxWidth, widthOf, lines)
        }
        return lines
    }

    private fun wrapParagraph(paragraph: String, maxWidth: Double, widthOf: (String) -> Double, out: MutableList<String>) {
        val tokens = ArrayList<String>()
        var i = 0
        while (i < paragraph.length) {
            val space = paragraph[i] == ' '
            var j = i
            while (j < paragraph.length && (paragraph[j] == ' ') == space) j++
            tokens.add(paragraph.substring(i, j))
            i = j
        }
        var line = StringBuilder()
        var afterWrap = false
        for (token in tokens) {
            val isSpace = token[0] == ' '
            if (isSpace && line.isEmpty() && afterWrap) continue // spaces at the start of a wrapped line are dropped
            if (widthOf(line.toString() + token) <= maxWidth) {
                line.append(token)
                continue
            }
            if (isSpace) {
                // the spaces do not fit: the line ends here and they are dropped
                if (line.isNotEmpty()) out.add(line.toString().trimEnd())
                line = StringBuilder()
                afterWrap = true
                continue
            }
            if (line.isNotEmpty()) {
                out.add(line.toString().trimEnd())
                line = StringBuilder()
                afterWrap = true
            }
            var rest = token
            while (widthOf(rest) > maxWidth) {
                var cut = rest.offsetByCodePoints(0, 1)
                while (cut < rest.length) {
                    val next = rest.offsetByCodePoints(cut, 1)
                    if (widthOf(rest.substring(0, next)) > maxWidth) break
                    cut = next
                }
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut)
                afterWrap = true
            }
            line.append(rest)
        }
        if (line.isNotEmpty()) out.add(line.toString().trimEnd())
    }

    /** Splits [lines] into pages of at most [linesPerPage] lines; empty input gives no pages. */
    fun paginate(lines: List<String>, linesPerPage: Int): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val per = maxOf(1, linesPerPage)
        return lines.chunked(per)
    }
}
