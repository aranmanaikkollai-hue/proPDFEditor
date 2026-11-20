package com.propdf.pdfedit.convert

/**
 * Parses page lists typed by a user. Pages are 1-based in the text and 0-based in the result.
 *
 * Grammar: comma separated items, each "N", "N-M", "N-" (N to the last page) or "-M" (first page to M).
 * Spaces are ignored. An empty text means all pages. Pages outside 1..pageCount and reversed ranges are errors.
 */
object PageRanges {

    sealed interface Result {
        class Ok(val indices: List<Int>) : Result
        class Error(val message: String) : Result
    }

    fun parse(spec: String, pageCount: Int): Result {
        if (pageCount <= 0) return Result.Error("The document has no pages")
        val text = spec.filter { !it.isWhitespace() }
        if (text.isEmpty()) return Result.Ok((0 until pageCount).toList())
        val selected = java.util.TreeSet<Int>()
        for (item in text.split(',')) {
            if (item.isEmpty()) return Result.Error("Empty item in the page list")
            val dash = item.indexOf('-')
            val first: Int
            val last: Int
            if (dash < 0) {
                first = number(item) ?: return Result.Error("Not a page number: " + item)
                last = first
            } else {
                if (item.indexOf('-', dash + 1) >= 0) return Result.Error("Not a page range: " + item)
                val left = item.substring(0, dash)
                val right = item.substring(dash + 1)
                if (left.isEmpty() && right.isEmpty()) return Result.Error("Not a page range: " + item)
                first = if (left.isEmpty()) 1 else (number(left) ?: return Result.Error("Not a page number: " + left))
                last = if (right.isEmpty()) pageCount else (number(right) ?: return Result.Error("Not a page number: " + right))
            }
            if (first < 1 || last < 1 || first > pageCount || last > pageCount) {
                return Result.Error("Pages must be between 1 and " + pageCount)
            }
            if (first > last) return Result.Error("The range " + item + " goes backwards")
            for (p in first..last) selected.add(p - 1)
        }
        return Result.Ok(selected.toList())
    }

    private fun number(s: String): Int? {
        if (s.isEmpty() || s.length > 9 || !s.all { it in '0'..'9' }) return null
        return s.toInt()
    }
}
