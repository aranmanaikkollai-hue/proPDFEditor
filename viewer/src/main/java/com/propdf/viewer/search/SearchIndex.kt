package com.propdf.viewer.search

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.room.*
import com.propdf.viewer.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline PDF text indexing and search using Room FTS4.
 *
 * Architecture:
 * - Extracts text from PDF pages using PdfRenderer or PDFBox
 * - Stores in FTS4 virtual table for fast full-text search
 * - Supports OCR fallback for scanned documents (via ML Kit, no cloud)
 * - Highlights search results with bounding boxes
 * - Recent search history stored in regular Room table
 */
class SearchIndex(
    private val context: Context,
    private val database: SearchDatabase
) {
    companion object {
        private const val TAG = "SearchIndex"
        private const val BATCH_SIZE = 50 // Pages to index per batch
    }

    private val searchDao = database.searchDao()
    private val ocrEngine = OcrSearchEngine(context)

    /** Cache of search results for current query */
    private val resultCache = ConcurrentHashMap<String, List<SearchResult>>()

    /**
     * Index a PDF document for searching.
     * Processes pages in batches to avoid blocking.
     *
     * @param documentId Unique document identifier
     * @param pageTexts Map of pageIndex -> extracted text
     */
    suspend fun indexDocument(documentId: String, pageTexts: Map<Int, String>) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Indexing document: $documentId, pages: ${pageTexts.size}")

        // Clear existing index for this document
        searchDao.deleteDocumentIndex(documentId)

        // Insert in batches
        pageTexts.entries.chunked(BATCH_SIZE).forEach { batch ->
            val entities = batch.map { (pageIndex, text) ->
                SearchIndexEntity(
                    documentId = documentId,
                    pageIndex = pageIndex,
                    pageText = text
                )
            }
            searchDao.insertIndexBatch(entities)
        }

        Log.i(TAG, "Indexing complete for $documentId")
    }

    /**
     * Search for text in the indexed document.
     * Returns results with highlighted bounding boxes.
     */
    suspend fun search(documentId: String, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val cacheKey = "$documentId:$query"
        resultCache[cacheKey]?.let { return@withContext it }

        // Use FTS MATCH for fast full-text search
        val results = searchDao.searchFts(documentId, query)

        val searchResults = results.map { entity ->
            SearchResult(
                pageIndex = entity.pageIndex,
                textSnippet = extractSnippet(entity.pageText, query),
                matchCount = countOccurrences(entity.pageText, query),
                boundingBoxes = calculateBoundingBoxes(entity.pageText, query)
            )
        }

        // Save to recent searches
        searchDao.insertRecentSearch(
            RecentSearchEntity(
                query = query,
                documentId = documentId,
                timestamp = System.currentTimeMillis(),
                resultCount = searchResults.size
            )
        )

        resultCache[cacheKey] = searchResults
        searchResults
    }

    /**
     * Stream search results as they're found (for large documents).
     */
    fun searchStream(documentId: String, query: String): Flow<SearchResult> = flow {
        if (query.isBlank()) return@flow

        val results = searchDao.searchFts(documentId, query)
        results.forEach { entity ->
            emit(SearchResult(
                pageIndex = entity.pageIndex,
                textSnippet = extractSnippet(entity.pageText, query),
                matchCount = countOccurrences(entity.pageText, query),
                boundingBoxes = calculateBoundingBoxes(entity.pageText, query)
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get recent searches for this document.
     */
    suspend fun getRecentSearches(documentId: String, limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        searchDao.getRecentSearches(documentId, limit).map { it.query }
    }

    /**
     * Clear search index for a document.
     */
    suspend fun clearDocumentIndex(documentId: String) = withContext(Dispatchers.IO) {
        searchDao.deleteDocumentIndex(documentId)
        // Clear result cache entries for this document
        resultCache.keys.filter { it.startsWith("$documentId:") }.forEach {
            resultCache.remove(it)
        }
    }

    /**
     * Index OCR text for scanned pages.
     */
    suspend fun indexOcrText(documentId: String, pageIndex: Int, ocrText: String) = withContext(Dispatchers.IO) {
        searchDao.insertIndexBatch(listOf(SearchIndexEntity(
            documentId = documentId,
            pageIndex = pageIndex,
            pageText = ocrText
        )))
    }

    private fun extractSnippet(text: String, query: String, maxLength: Int = 120): String {
        val index = text.indexOf(query, ignoreCase = true)
        if (index == -1) return text.take(maxLength)

        val start = maxOf(0, index - maxLength / 2)
        val end = minOf(text.length, index + query.length + maxLength / 2)
        return text.substring(start, end)
    }

    private fun countOccurrences(text: String, query: String): Int {
        var count = 0
        var index = text.indexOf(query, ignoreCase = true)
        while (index != -1) {
            count++
            index = text.indexOf(query, index + 1, ignoreCase = true)
        }
        return count
    }

    private fun calculateBoundingBoxes(text: String, query: String): List<RectF> {
        // Simplified: In production, this would use PDF text extraction
        // to get actual glyph positions. For now, return placeholder rects.
        val count = countOccurrences(text, query)
        return List(count) { RectF(0f, 0f, 100f, 20f) }
    }
}
