package com.propdf.editor.ui.viewer

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-text search across a PDF using PDFBox, returning the page and PDF-point-space
 * bounding box of every match so the UI can jump to and highlight results.
 */
class PdfSearchEngine {

    suspend fun search(file: File, query: String, maxResults: Int = 500): List<PdfSearchMatch> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val lowerQuery = query.lowercase()
            val matches = mutableListOf<PdfSearchMatch>()

            PDDocument.load(file).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        if (matches.size < maxResults) {
                            findMatchesInLine(text, textPositions, lowerQuery, currentPageNo - 1, matches)
                        }
                        super.writeString(text, textPositions)
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = 1
                stripper.endPage = document.numberOfPages
                // Triggers writeString() for every line; we only care about the side effects above.
                stripper.getText(document)
            }

            matches.take(maxResults)
        }

    private fun findMatchesInLine(
        text: String,
        textPositions: List<TextPosition>,
        lowerQuery: String,
        pageIndex: Int,
        out: MutableList<PdfSearchMatch>
    ) {
        val lowerText = text.lowercase()
        var searchFrom = 0
        while (out.size < 500) {
            val idx = lowerText.indexOf(lowerQuery, searchFrom)
            if (idx == -1) break
            val endIdx = (idx + lowerQuery.length).coerceAtMost(textPositions.size)
            if (idx < textPositions.size && endIdx > idx) {
                val positions = textPositions.subList(idx, endIdx)
                if (positions.isNotEmpty()) {
                    val left = positions.minOf { it.xDirAdj }
                    val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }
                    val bottom = positions.maxOf { it.yDirAdj }
                    val top = positions.minOf { it.yDirAdj - it.heightDir }
                    val snippetStart = (idx - 20).coerceAtLeast(0)
                    val snippetEnd = (endIdx + 20).coerceAtMost(text.length)
                    out.add(
                        PdfSearchMatch(
                            pageIndex = pageIndex,
                            rect = RectF(left, top, right, bottom),
                            snippet = text.substring(snippetStart, snippetEnd).trim()
                        )
                    )
                }
            }
            searchFrom = idx + 1
        }
    }
}
