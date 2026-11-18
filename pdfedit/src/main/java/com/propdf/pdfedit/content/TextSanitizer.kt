package com.propdf.pdfedit.content

/** Makes text safe for a font that cannot draw every character, and says what it had to change. */
object TextSanitizer {

    class Result(val text: String, val dropped: List<String>)

    /**
     * Keeps characters [canEncode] accepts. A character the font cannot draw becomes "?" when the font has that,
     * otherwise it is removed; either way it is listed in [Result.dropped]. Newlines are kept, carriage returns are
     * removed and tabs become one space.
     */
    fun sanitize(text: String, canEncode: (String) -> Boolean): Result {
        val out = StringBuilder()
        val dropped = ArrayList<String>()
        val questionOk by lazy { canEncode("?") }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val s = String(Character.toChars(cp))
            when {
                cp == '\n'.code -> out.append('\n')
                cp == '\r'.code -> {}
                cp == '\t'.code -> out.append(' ')
                canEncode(s) -> out.append(s)
                else -> {
                    if (s !in dropped) dropped.add(s)
                    if (questionOk) out.append('?')
                }
            }
        }
        return Result(out.toString(), dropped)
    }
}
