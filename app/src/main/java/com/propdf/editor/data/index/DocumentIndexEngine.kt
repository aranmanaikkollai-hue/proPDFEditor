package com.propdf.editor.data.index

import android.content.Context
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.data.local.entity.SearchIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-text indexing engine for PDF documents.
 * 
 * NOTE: For production full-text search, add the PDFBox dependency:
 *   implementation 'com.tom_roush:pdfbox-android:2.0.27.0'
 * And uncomment the PDFBox extraction code below.
 * 
 * Without PDFBox, this engine indexes filenames and basic metadata only.
 */
@Singleton
class DocumentIndexEngine @Inject constructor(
    private val context: Context,
    private val searchIndexDao: SearchIndexDao
) {

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "must", "shall",
            "can", "need", "dare", "ought", "used", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below",
            "between", "under", "and", "but", "or", "yet", "so", "if",
            "because", "although", "though", "while", "where", "when",
            "that", "which", "who", "whom", "whose", "what", "this",
            "these", "those", "i", "you", "he", "she", "it", "we", "they",
            "me", "him", "her", "us", "them", "my", "your", "his",
            "its", "our", "their", "mine", "yours", "hers", "ours",
            "theirs", "myself", "yourself", "himself", "herself",
            "itself", "ourselves", "yourselves", "themselves"
        )
        private const val MIN_WORD_LENGTH = 3
        private const val MAX_WORD_LENGTH = 50
    }

    /**
     * Index a PDF file by extracting text from all pages.
     * 
     * Without PDFBox: indexes filename words as a fallback.
     * With PDFBox: extracts full page text for deep search.
     */
    suspend fun indexDocument(documentId: Long, pdfFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Clear old index entries
            searchIndexDao.deleteByDocument(documentId)

            val entries = mutableListOf<SearchIndexEntity>()

            /*
            // === PDFBOX FULL-TEXT EXTRACTION (uncomment when PDFBox is added) ===
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdfFile).use { document ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                for (pageNum in 1..document.numberOfPages) {
                    stripper.startPage = pageNum
                    stripper.endPage = pageNum
                    val text = stripper.getText(document)
                    val words = extractWords(text)
                    words.forEach { (word, position) ->
                        entries.add(
                            SearchIndexEntity(
                                documentId = documentId,
                                pageNumber = pageNum,
                                word = word.lowercase(),
                                context = getContext(text, position),
                                positionX = position.first,
                                positionY = position.second
                            )
                        )
                    }
                }
            }
            */

            // === FALLBACK: Index filename words ===
            val fileName = pdfFile.nameWithoutExtension
            val nameWords = fileName.split(Regex("[^a-zA-Z0-9]+"))
                .filter { it.length in MIN_WORD_LENGTH..MAX_WORD_LENGTH }
                .filterNot { STOP_WORDS.contains(it.lowercase()) }

            nameWords.forEachIndexed { index, word ->
                entries.add(
                    SearchIndexEntity(
                        documentId = documentId,
                        pageNumber = 0,
                        word = word.lowercase(),
                        context = "From filename: $fileName",
                        positionX = index * 10f,
                        positionY = 0f
                    )
                )
            }

            // Batch insert for performance
            entries.chunked(500).forEach { batch ->
                searchIndexDao.insertAll(batch)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Search for documents matching the query.
     */
    suspend fun search(query: String): List<Long> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.length < MIN_WORD_LENGTH) return emptyList()
        return searchIndexDao.searchDocuments(normalizedQuery)
    }

    /**
     * Extract words with approximate positions from text.
     */
    private fun extractWords(text: String): List<Pair<String, Pair<Float, Float>>> {
        val words = mutableListOf<Pair<String, Pair<Float, Float>>>()
        val regex = Regex("\b[a-zA-Z0-9]+\b")
        val matches = regex.findAll(text)

        matches.forEachIndexed { index, match ->
            val word = match.value
            if (word.length in MIN_WORD_LENGTH..MAX_WORD_LENGTH &&
                !STOP_WORDS.contains(word.lowercase())
            ) {
                val x = (index % 80) * 10f
                val y = (index / 80) * 15f
                words.add(word to Pair(x, y))
            }
        }
        return words
    }

    /**
     * Get surrounding context for a word in text.
     */
    private fun getContext(text: String, position: Pair<Float, Float>, windowSize: Int = 40): String {
        val charPos = ((position.second / 15f) * 80 + (position.first / 10f)).toInt()
        val start = (charPos - windowSize).coerceAtLeast(0)
        val end = (charPos + windowSize).coerceAtMost(text.length)
        return text.substring(start, end).replace("\s+".toRegex(), " ").trim()
    }

    /**
     * Delete all index entries for a document.
     */
    suspend fun removeDocumentIndex(documentId: Long) {
        searchIndexDao.deleteByDocument(documentId)
    }

    /**
     * Get index statistics.
     */
    suspend fun getIndexStats(): IndexStats = withContext(Dispatchers.IO) {
        IndexStats(
            totalDocuments = 0,
            totalWords = 0,
            lastIndexed = null
        )
    }
}

data class IndexStats(
    val totalDocuments: Int,
    val totalWords: Int,
    val lastIndexed: Long?
)
