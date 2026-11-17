package com.propdf.viewer.ui

/** Parsing for the "go to page" dialog. */
object PageJump {

    /**
     * Converts what the user typed (a 1-based page number) into a 0-based page index.
     * Returns null when the text is empty, not plain ASCII digits, or outside 1..[totalPages].
     */
    fun parse(input: String, totalPages: Int): Int? {
        if (totalPages <= 0) return null
        val text = input.trim()
        if (text.isEmpty() || text.length > 9) return null
        if (!text.all { it in '0'..'9' }) return null
        val number = text.toInt()
        return if (number in 1..totalPages) number - 1 else null
    }
}
