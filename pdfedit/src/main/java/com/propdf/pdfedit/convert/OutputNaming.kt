package com.propdf.pdfedit.convert

/** File names for converted output. Pure, so it is unit tested on the JVM. */
object OutputNaming {
    private const val MAX_BASE = 80

    /** Turns a document name into a safe base name: no extension, no path characters, at most 80 characters. */
    fun baseName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.lowercase().endsWith(".pdf")) name = name.substring(0, name.length - 4)
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
            val keep = Character.isLetterOrDigit(cp) || mark || cp == '.'.code || cp == '-'.code || cp == '_'.code || cp == ' '.code
            sb.appendCodePoint(if (keep) cp else '_'.code)
        }
        var cleaned = sb.toString().replace(Regex("_{2,}"), "_").trim('.', ' ', '_')
        if (cleaned.codePointCount(0, cleaned.length) > MAX_BASE) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_BASE)).trim('.', ' ', '_')
        }
        return if (cleaned.isEmpty()) "document" else cleaned
    }

    /** Name of the image for 1-based page [pageNumber]; the number is zero padded to at least 3 digits. */
    fun pageImageName(base: String, pageNumber: Int, totalPages: Int, extension: String): String {
        val width = maxOf(3, totalPages.toString().length)
        return base + "_page_" + pageNumber.toString().padStart(width, '0') + "." + extension
    }

    fun textName(base: String): String = base + ".txt"
}
