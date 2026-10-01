package com.propdf.editor.ui

import com.tom_roush.pdfbox.pdmodel.PDDocument

/** Page duplication used by the Page Editor (extracted so it can be regression-tested). */
object PageDuplicator {

    /**
     * Appends one copy of every selected page (0-based indexes) at the end of [doc], in ascending
     * order of the selection, and returns how many pages were added.
     *
     * PDDocument.importPage() already inserts the imported copy into the document. Calling
     * addPage() on its result as well inserted the same page object a second time, so a single
     * duplicate request produced two extra pages.
     */
    fun appendCopies(doc: PDDocument, pages: List<Int>): Int {
        val originalCount = doc.numberOfPages
        var added = 0
        pages.sorted().forEach { index ->
            if (index in 0 until originalCount) {
                doc.importPage(doc.getPage(index))
                added++
            }
        }
        return added
    }
}
